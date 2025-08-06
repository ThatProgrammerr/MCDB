package com.decacagle;

import com.decacagle.data.DataUtilities;
import com.decacagle.data.DataWorker;
import com.decacagle.data.TableManager;
import com.decacagle.endpoints.*;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

public class APIManager {

    private Logger logger;
    private World world;
    private DecaDB plugin;
    private DataWorker worker;
    private TableManager tableManager;
    private HttpServer server;
    private Set<String> activeContexts;

    public APIManager(Logger logger, World world, DecaDB plugin) {
        this.logger = logger;
        this.world = world;
        this.plugin = plugin;
        this.worker = new DataWorker(logger, world, plugin);
        this.tableManager = new TableManager(logger, world, worker);
        this.activeContexts = new HashSet<>();
        startHTTPServer();
    }

    public void startHTTPServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(8000), 0);

            // Updated handlers now use TableManager for recycling support
            server.createContext("/upload", new UploadHandler(server, logger, world, plugin, worker));
            server.createContext("/deleteFile", new DeleteFileHandler(server, logger, world, plugin, worker));
            server.createContext("/query", new QueryHandler(logger, world, plugin, worker));

            // Initialize core system tables
            initializeSystemTables();

            // On server launch, read through list of current saved files and create routes for those files
            addRoutes(server);

            server.setExecutor(null);
            server.start();

            logger.info("HTTP Server started!");

        } catch (IOException e) {
            logger.severe("Error starting HTTP server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Initialize system tables required for operation
     */
    private void initializeSystemTables() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Ensure core system tables exist
//            ensureTableExists("users");
//            ensureTableExists("authTokens");
            // freeChunks table is created automatically by TableManager when needed

            logger.info("System tables initialized");
        });
    }

    /**
     * Ensures a table exists, creates it if it doesn't
     */
    private void ensureTableExists(String tableName) {
        int tableIndex = worker.getTableIndex(tableName, 1);
        if (tableIndex == 0) {
            tableManager.createTable(tableName);
            logger.info("Created system table: " + tableName);
        }
    }

    public void addRoutes(HttpServer server) {
        try {
            Bukkit.getScheduler().runTask(plugin, () -> addFileRoutes(server));
        } catch (Exception e) {
            logger.severe("Error adding routes: " + e.getMessage());
            e.printStackTrace();
        }
    }

    int indexOffset = -1;

    public void addFileRoutes(HttpServer server) {
        String startIndex = worker.readChunkSafely(0, -1, false, 1);

        if (!startIndex.isEmpty() && !startIndex.equals("0")) {
            Set<String> processedTitles = new HashSet<>();
            Set<Integer> processedIndices = new HashSet<>();

            int currentIndex = Integer.parseInt(startIndex);

            // Validate the starting index
            if (!isValidFileIndex(currentIndex)) {
                logger.warning("Invalid starting file index: " + currentIndex);
                return;
            }

            String currentMetadata = worker.readChunkSafely(0, -currentIndex + indexOffset, false, 1);

            if (!DataUtilities.isValidFileMetadata(currentMetadata)) {
                logger.warning("Invalid file metadata at starting index: " + currentIndex);
                return;
            }

            String title = DataUtilities.parseTitle(currentMetadata);
            int nextIndex = DataUtilities.parseNextIndexTable(currentMetadata);

            // Process first file
            if (createFileRoute(server, title, currentIndex, processedTitles, processedIndices)) {
                logger.info("Created route for file: " + title + " (index: " + currentIndex + ")");
            }

            // Process remaining files in the linked list
            int maxIterations = 1000; // Prevent infinite loops
            int iterations = 0;

            while (nextIndex != 0 && iterations < maxIterations) {
                iterations++;

                if (processedIndices.contains(nextIndex)) {
                    logger.warning("Circular reference detected in file chain at index: " + nextIndex);
                    break;
                }

                if (!isValidFileIndex(nextIndex)) {
                    logger.warning("Invalid file index in chain: " + nextIndex);
                    break;
                }

                currentIndex = nextIndex;
                currentMetadata = worker.readChunkSafely(0, -currentIndex + indexOffset, false, 1);

                if (!DataUtilities.isValidFileMetadata(currentMetadata)) {
                    logger.warning("Invalid file metadata at index: " + currentIndex);
                    break;
                }

                title = DataUtilities.parseTitle(currentMetadata);
                nextIndex = DataUtilities.parseNextIndexTable(currentMetadata);

                if (createFileRoute(server, title, currentIndex, processedTitles, processedIndices)) {
                    logger.info("Created route for file: " + title + " (index: " + currentIndex + ")");
                }
            }

            if (iterations >= maxIterations) {
                logger.warning("Maximum iterations reached while processing file chain. Possible corruption.");
            }

            logger.info("File routes initialization complete. Processed " + processedIndices.size() + " files.");
        } else {
            logger.info("No files found to create routes for.");
        }
    }

    /**
     * Validates that a file index is reasonable
     */
    private boolean isValidFileIndex(int index) {
        return index > 0 && index < 100000; // Reasonable bounds
    }

    /**
     * Creates a file route, handling duplicates and errors
     */
    private boolean createFileRoute(HttpServer server, String title, int index,
                                    Set<String> processedTitles, Set<Integer> processedIndices) {
        try {
            if (processedIndices.contains(index)) {
                logger.warning("Duplicate file index detected: " + index);
                return false;
            }

            String contextPath = DataUtilities.contextNameBuilder(title);

            if (processedTitles.contains(title)) {
                logger.warning("Duplicate file title detected: " + title + " - adding index suffix");
                contextPath = DataUtilities.contextNameBuilder(title + "_" + index);
            }

            if (activeContexts.contains(contextPath)) {
                logger.warning("Context path already exists: " + contextPath);
                return false;
            }

            server.createContext(contextPath, new FileReader(logger, world, plugin, worker, index));
            activeContexts.add(contextPath);
            processedTitles.add(title);
            processedIndices.add(index);

            return true;
        } catch (Exception e) {
            logger.severe("Error creating route for file " + title + " (index " + index + "): " + e.getMessage());
            return false;
        }
    }

    /**
     * Safely removes a context and updates tracking
     */
    public void removeFileRoute(String contextPath) {
        try {
            server.removeContext(contextPath);
            activeContexts.remove(contextPath);
            logger.info("Removed route: " + contextPath);
        } catch (Exception e) {
            logger.warning("Error removing route " + contextPath + ": " + e.getMessage());
        }
    }

    /**
     * Gets the set of active contexts
     */
    public Set<String> getActiveContexts() {
        return new HashSet<>(activeContexts);
    }

    /**
     * Validates the integrity of the file system
     */
    public void validateFileSystemIntegrity() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            logger.info("Starting file system integrity check...");

            String startIndex = worker.readChunkSafely(0, -1, false, 1);
            if (startIndex.isEmpty() || startIndex.equals("0")) {
                logger.info("No files to validate.");
                return;
            }

            Set<Integer> foundIndices = new HashSet<>();
            int currentIndex = Integer.parseInt(startIndex);
            int fileCount = 0;
            int maxFiles = 1000;

            while (currentIndex != 0 && fileCount < maxFiles) {
                if (foundIndices.contains(currentIndex)) {
                    logger.severe("CORRUPTION: Circular reference detected at index " + currentIndex);
                    break;
                }

                String metadata = worker.readChunkSafely(0, -currentIndex + indexOffset, false, 1);
                if (!DataUtilities.isValidFileMetadata(metadata)) {
                    logger.severe("CORRUPTION: Invalid metadata at index " + currentIndex);
                    break;
                }

                foundIndices.add(currentIndex);
                fileCount++;

                currentIndex = DataUtilities.parseNextIndexTable(metadata);
            }

            logger.info("File system integrity check complete. Found " + fileCount + " valid files.");
        });
    }
}