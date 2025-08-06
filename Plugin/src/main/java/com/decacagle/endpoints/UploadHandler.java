package com.decacagle.endpoints;

import com.decacagle.DecaDB;
import com.decacagle.data.DataUtilities;
import com.decacagle.data.DataWorker;
import com.decacagle.data.TableManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;

import java.util.logging.Logger;

public class UploadHandler extends APIEndpoint {

    private HttpServer server;
    private TableManager tableManager;

    private int indexOffset = -1;

    public UploadHandler(HttpServer server, Logger logger, World world, DecaDB plugin, DataWorker worker) {
        super(logger, world, plugin, worker);

        this.server = server;
        this.tableManager = new TableManager(logger, world, worker);
    }

    public void handle(HttpExchange exchange) {
        addCorsHeaders(exchange);

        if (!preflightCheck(exchange)) {
            runSynchronously(() -> writeFile(exchange));
        }
    }

    public void writeFile(HttpExchange exchange) {

        String uploadBody = parseExchangeBody(exchange);
        String[] bodyParts = uploadBody.split(";");

        if (bodyParts.length != 3) {
            respond(exchange, 400, "Bad Request: Body of request should be {fileTitle};{mime};{base64Data}, received: " + bodyParts[0] + ";" + bodyParts[1] + ";data");
            return;
        }

        String fileTitle = bodyParts[0];
        String fileMime = bodyParts[1];
        String fileData = bodyParts[2];

        // Check for duplicate file titles
        if (fileExists(fileTitle)) {
            respond(exchange, 400, "Bad Request: A file with the title '" + fileTitle + "' already exists!");
            return;
        }

        logger.info("Successfully received file upload");
        logger.info("File Title: " + fileTitle);
        logger.info("File Mime: " + fileMime);

        int index = getNextIndex();
        int last = index - 1;

        String newFileMetadata = DataUtilities.fileMetadataBuilder(fileTitle, fileMime, last, 0);

        // Ensure the chunk is completely clean before writing
        cleanChunkCompletely(0, -index + indexOffset);
        cleanChunkCompletely(1, -index + indexOffset);

        boolean metadataWriteResult = worker.writeToChunk(newFileMetadata, 0, -index + indexOffset, false, 1);

        if (metadataWriteResult) {

            boolean writeFileResult = worker.writeToChunk(fileData, 1, -index + indexOffset, true, 1);

            if (writeFileResult) {

                updateLastMetadata(index);

                String newContext = DataUtilities.contextNameBuilder(fileTitle);

                try {
                    server.createContext(newContext, new FileReader(logger, world, plugin, worker, index));
                } catch (Exception e) {
                    respond(exchange, 500, "Internal Server Error: Failed to create route -- " + e.getMessage());
                    return;
                }

                placeSign(fileTitle, fileMime, index);

                logger.info("Created new route: " + newContext);

                exchange.getResponseHeaders().add("Content-Type", "application/json");
                respond(exchange, 200, "{\"message\":\"Wrote file " + fileTitle + " successfully!\", \"link\": \"http://localhost:8000" + newContext + "\",\"fileId\":" + index + "}");

            } else {
                respond(exchange, 500, "Internal Server Error: Failed to write base64 data!");
            }

        } else {
            respond(exchange, 400, "Bad Request: Failed to write file metadata!");
        }

    }

    /**
     * Stores file metadata in the database as a separate operation
     * This is called AFTER the file is completely uploaded to avoid conflicts
     */
    private void storeFileMetadataInDatabase(String fileTitle, String fileMime, int fileSize, int fileId, String link) {
        // This will be handled by the frontend's separate database insert call
        // We don't do it here to avoid chunk index conflicts
        logger.info("File " + fileTitle + " stored with ID " + fileId + ", metadata will be stored separately by frontend");
    }

    /**
     * Checks if a file with the given title already exists
     */
    private boolean fileExists(String fileTitle) {
        String startIndexText = worker.readChunkSafely(0, -1, false, 1);

        if (startIndexText.isEmpty() || startIndexText.equals("0")) {
            return false;
        }

        int currentIndex = Integer.parseInt(startIndexText);
        String currentMetadata = worker.readChunkSafely(0, -currentIndex + indexOffset, false, 1);

        if (!DataUtilities.isValidFileMetadata(currentMetadata)) {
            return false;
        }

        String title = DataUtilities.parseTitle(currentMetadata);
        int nextIndex = DataUtilities.parseNextIndexTable(currentMetadata);

        if (title.equals(fileTitle)) {
            return true;
        }

        while (nextIndex != 0) {
            currentIndex = nextIndex;
            currentMetadata = worker.readChunkSafely(0, -currentIndex + indexOffset, false, 1);

            if (!DataUtilities.isValidFileMetadata(currentMetadata)) {
                break;
            }

            title = DataUtilities.parseTitle(currentMetadata);
            nextIndex = DataUtilities.parseNextIndexTable(currentMetadata);

            if (title.equals(fileTitle)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Completely cleans a chunk by deleting it thoroughly
     */
    private void cleanChunkCompletely(int x, int z) {
        // First delete normally
        worker.deleteChunkCompletely(x, z, false, 1);

        // Then ensure any remaining blocks are cleaned
        int startX = x * 16;
        int startZ = -1 + (z * 16);

        for (int chunkX = startX; chunkX < startX + 16; chunkX++) {
            for (int chunkZ = startZ; chunkZ > startZ - 16; chunkZ--) {
                for (int y = -64; y < 320; y++) {
                    Block block = world.getBlockAt(chunkX, y, chunkZ);
                    if (DataUtilities.isWoolBlock(block.getType())) {
                        if (y == -64) {
                            block.setType(Material.GRASS_BLOCK);
                        } else {
                            block.setType(Material.AIR);
                        }
                    }
                }
            }
        }
    }

    /**
     * Gets a free file chunk from the recycling system
     * Only gets chunks from the file coordinate space
     */
    private int getFreeFileChunk() {
        return tableManager.getFreeChunk("file", 0);
    }

    /**
     * Gets next available file index, checking recycled chunks first
     */
    public int getNextIndex() {
        // First, try to get a recycled chunk
        int freeChunk = getFreeFileChunk();
        if (freeChunk > 0) {
            logger.info("Reusing free file chunk: " + freeChunk);
            return freeChunk;
        }

        // No free chunks available, use existing sequential allocation logic
        String startIndexText = worker.readChunkSafely(0, -1, false, 1);

        if (startIndexText.isEmpty() || startIndexText.equals("0")) {
            worker.writeToChunk("1", 0, -1, false, 1);
            return 1;
        } else {
            int currentIndex = Integer.parseInt(startIndexText);
            String currentData = worker.readChunkSafely(0, -currentIndex + indexOffset, false, 1);
            int nextIndex = DataUtilities.parseNextIndexTable(currentData);

            while (nextIndex != 0) {
                currentIndex = nextIndex;
                currentData = worker.readChunkSafely(0, -currentIndex + indexOffset, false, 1);
                nextIndex = DataUtilities.parseNextIndexTable(currentData);
            }

            return currentIndex + 1;
        }
    }

    public void placeSign(String fileTitle, String fileMime, int fileIndex) {
        Block block = world.getBlockAt(-1, -63, -(fileIndex * 16) + (indexOffset * 16) - 1);

        block.setType(Material.OAK_SIGN);

        Sign sign = (Sign) block.getState();

        sign.setLine(0, "FILE #" + fileIndex);
        sign.setLine(1, fileTitle);
        sign.setLine(2, fileMime);

        sign.update();
    }

    public void updateLastMetadata(int index) {
        if (index != 1) {
            String metadata = worker.readChunkSafely(0, -(index - 1) + indexOffset, false, 1);

            if (DataUtilities.isValidFileMetadata(metadata)) {
                String title = DataUtilities.parseTitle(metadata);
                String mime = DataUtilities.parseFileMime(metadata);
                int last = DataUtilities.parseLastIndexTable(metadata);

                worker.deleteChunkCompletely(0, -(index - 1) + indexOffset, false, 1);

                String newMetadata = DataUtilities.fileMetadataBuilder(title, mime, last, index);

                worker.writeToChunk(newMetadata, 0, -(index - 1) + indexOffset, false, 1);
            }
        }
    }
}