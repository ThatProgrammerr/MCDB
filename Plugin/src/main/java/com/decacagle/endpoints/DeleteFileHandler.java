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

import java.util.logging.Logger;

public class DeleteFileHandler extends APIEndpoint {

    private HttpServer server;
    private TableManager tableManager;
    private int indexOffset = -1;

    public DeleteFileHandler(HttpServer server, Logger logger, World world, DecaDB plugin, DataWorker worker) {
        super(logger, world, plugin, worker);

        this.server = server;
        this.tableManager = new TableManager(logger, world, worker);
    }

    public void handle(HttpExchange exchange) {
        addCorsHeaders(exchange);

        if (!preflightCheck(exchange)) {

            String queryString = exchange.getRequestURI().getQuery();
            if (queryString == null || queryString.length() < 3) {
                respond(exchange, 400, "Bad Request: Missing query parameter");
                return;
            }

            try {
                // Support both 'q=' and 'id=' parameter formats for backwards compatibility
                int index;
                if (queryString.startsWith("q=")) {
                    index = Integer.parseInt(queryString.substring(2));
                } else if (queryString.startsWith("id=")) {
                    index = Integer.parseInt(queryString.substring(3));
                } else {
                    respond(exchange, 400, "Bad Request: Invalid parameter format. Use 'q=' or 'id='");
                    return;
                }

                runSynchronously(() -> deleteFile(server, exchange, index));
            } catch (NumberFormatException e) {
                respond(exchange, 400, "Bad Request: Invalid parameter value");
            }
        }
    }

    /**
     * Adds a deleted file chunk to the recycling system
     */
    private void addFileToFreeChunks(int fileIndex) {
        tableManager.addToFreeChunks(fileIndex, "file", 0);
    }

    /**
     * Completely cleans file chunks by removing all data
     */
    private void cleanFileChunksCompletely(int index) {
        // Clean metadata chunk
        cleanChunkCompletely(0, -index + indexOffset);

        // Clean data chunk(s) - files can span multiple chunks
        cleanChunkCompletely(1, -index + indexOffset);

        // Clean any additional chunks that might contain file data
        // This is important for large files that span multiple chunks
        for (int i = 2; i <= 5; i++) {  // Check a few extra chunks for large files
            try {
                String testRead = worker.readChunkSafely(i, -index + indexOffset, false, 1);
                if (!testRead.isEmpty() && !testRead.equals("Failed")) {
                    cleanChunkCompletely(i, -index + indexOffset);
                } else {
                    break;  // No more data chunks
                }
            } catch (Exception e) {
                break;  // No more data chunks
            }
        }
    }

    /**
     * Completely cleans a chunk by deleting it thoroughly
     */
    private void cleanChunkCompletely(int x, int z) {
        // First delete using the worker's method
        worker.deleteChunkCompletely(x, z, false, 1);

        // Then ensure any remaining blocks are cleaned manually
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
     * Validates that a file index exists and has valid metadata
     */
    private boolean validateFileExists(int index) {
        if (index <= 0) {
            return false;
        }

        String metadata = worker.readChunkSafely(0, -index + indexOffset, false, 1);
        return DataUtilities.isValidFileMetadata(metadata);
    }

    /**
     * Deletes the file stored at the given index and responds to the HTTP request.
     * Modifies metadata of the leftmost and rightmost files in the tree to update their last and next index values
     * @param server The HttpServer object passed from Handler constructor, used to remove link to the file after deletion
     * @param exchange The HttpExchange object passed from the initial HttpHandler handle method
     * @param index    The index of the file to be deleted
     */
    public void deleteFile(HttpServer server, HttpExchange exchange, int index) {
        if (!validateFileExists(index)) {
            respond(exchange, 400, "Bad Request: File doesn't exist or has corrupted metadata");
            return;
        }

        String metadata = worker.readChunkSafely(0, -index + indexOffset, false, 1);
        String targetTitle = DataUtilities.parseTitle(metadata);
        int lastIndex = DataUtilities.parseLastIndexTable(metadata);
        int nextIndex = DataUtilities.parseNextIndexTable(metadata);

        logger.info("Deleting file: " + targetTitle + " (index: " + index + ")");
        logger.info("File lastIndex: " + lastIndex + ", nextIndex: " + nextIndex);

        // Update the linked list structure
        updateLinkedListForDeletion(lastIndex, nextIndex, index);

        // Remove the HTTP server context for this file
        String contextPath = DataUtilities.contextNameBuilder(targetTitle);
        try {
            server.removeContext(contextPath);
            logger.info("Removed HTTP context: " + contextPath);
        } catch (Exception e) {
            logger.warning("Failed to remove HTTP context " + contextPath + ": " + e.getMessage());
        }

        // Completely clean all chunks used by this file
        cleanFileChunksCompletely(index);

        // Remove the physical sign
        deleteSign(index);

        // Add the deleted file chunk to free chunks for recycling
        addFileToFreeChunks(index);

        logger.info("Successfully deleted file " + targetTitle + " and added index " + index + " to free chunks");
        respond(exchange, 200, "Deleted file with success: " + targetTitle);
    }

    /**
     * Updates the linked list structure when deleting a file
     */
    private void updateLinkedListForDeletion(int lastIndex, int nextIndex, int currentIndex) {
        // if target file has no last index, update start index to be target's next index
        if (lastIndex == 0) {
            worker.deleteChunkCompletely(0, -1, false, 1);
            if (nextIndex != 0) {
                worker.writeToChunk("" + nextIndex, 0, -1, false, 1);
            } else {
                worker.writeToChunk("0", 0, -1, false, 1);
            }
        } else {
            // otherwise, update nextIndex of target's last to be target's nextIndex
            String lastMeta = worker.readChunkSafely(0, -lastIndex + indexOffset, false, 1);

            if (DataUtilities.isValidFileMetadata(lastMeta)) {
                String lastTitle = DataUtilities.parseTitle(lastMeta);
                String lastMime = DataUtilities.parseFileMime(lastMeta);
                int lastLast = DataUtilities.parseLastIndexTable(lastMeta);

                String newMeta = DataUtilities.fileMetadataBuilder(lastTitle, lastMime, lastLast, nextIndex);

                logger.info("Updating metadata for previous file in the chain, setting nextIndex to " + nextIndex);

                worker.deleteChunkCompletely(0, -lastIndex + indexOffset, false, 1);
                worker.writeToChunk(newMeta, 0, -lastIndex + indexOffset, false, 1);
            }
        }

        // if target file has a nextIndex, update nextIndex's last to be target's last
        if (nextIndex != 0) {
            String nextMeta = worker.readChunkSafely(0, -nextIndex + indexOffset, false, 1);

            if (DataUtilities.isValidFileMetadata(nextMeta)) {
                String nextTitle = DataUtilities.parseTitle(nextMeta);
                String nextMime = DataUtilities.parseFileMime(nextMeta);
                int nextNext = DataUtilities.parseNextIndexTable(nextMeta);

                logger.info("Updating metadata for next file in the chain, setting lastIndex to " + lastIndex);

                String newMeta = DataUtilities.fileMetadataBuilder(nextTitle, nextMime, lastIndex, nextNext);

                worker.deleteChunkCompletely(0, -nextIndex + indexOffset, false, 1);
                worker.writeToChunk(newMeta, 0, -nextIndex + indexOffset, false, 1);
            }
        }
    }

    public void deleteSign(int fileIndex) {
        Block signBlock = world.getBlockAt(-1, -63, -(fileIndex * 16) + (indexOffset * 16) - 1);
        signBlock.setType(Material.AIR);
    }
}