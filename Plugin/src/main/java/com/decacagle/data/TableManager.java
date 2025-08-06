package com.decacagle.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;

import java.util.logging.Logger;

public class TableManager {

    private Logger logger;
    private World world;
    private DataWorker worker;

    private final int indexOffset = 1;

    public TableManager(Logger logger, World world, DataWorker worker) {
        this.logger = logger;
        this.world = world;
        this.worker = worker;
    }

    // ==================== FREE CHUNK RECYCLING SYSTEM ====================

    /**
     * Ensures the freeChunks table exists for tracking deleted chunks
     * Uses sequential allocation to avoid infinite recursion
     */
    private void ensureFreeChunksTableExists() {
        int freeChunksIndex = worker.getTableIndex("freeChunks", indexOffset);
        if (freeChunksIndex == 0) {
            // Create the freeChunks table using sequential allocation to avoid recursion
            createTableWithoutRecycling("freeChunks");
            logger.info("Created freeChunks tracking table");
        }
    }

    /**
     * Creates a table using only sequential allocation (no recycling)
     * Used internally to prevent infinite recursion when creating freeChunks table
     */
    private MethodResponse createTableWithoutRecycling(String tableTitle) {
        logger.info("Creating table without recycling: " + tableTitle);

        int index = getNextTableIndexSequential();
        int last = index - 1;

        String newFileMetadata = DataUtilities.tableMetadataBuilder(tableTitle, last, 0);

        boolean metadataWriteResult = worker.writeToChunk(newFileMetadata, 0, index + indexOffset, false, 1);

        if (metadataWriteResult) {
            updateLastTableMetadata(index);
            placeTableSign(tableTitle, index);

            return new MethodResponse(200, "Wrote table " + tableTitle + " successfully!", "Wrote table " + tableTitle + " successfully!", false);
        } else {
            return new MethodResponse(400, "Bad Request: Failed to write table metadata!", null, true);
        }
    }

    /**
     * Sequential table index allocation without recycling check
     */
    private int getNextTableIndexSequential() {
        String startIndexText = worker.readChunk(0, 1, false, 1);

        if (startIndexText.isEmpty() || startIndexText.equals("0")) {
            worker.writeToChunk("1", 0, 1, false, 1);
            return 1;
        } else {
            int currentIndex = Integer.parseInt(startIndexText);
            String currentData = worker.readChunk(0, currentIndex + indexOffset, false, 1);
            int nextIndex = DataUtilities.parseNextIndexTable(currentData);

            while (nextIndex != 0) {
                currentIndex = nextIndex;
                currentData = worker.readChunk(0, currentIndex + indexOffset, false, 1);
                nextIndex = DataUtilities.parseNextIndexTable(currentData);
            }

            return currentIndex + 1;
        }
    }

    /**
     * Adds a deleted chunk to the free chunks table for recycling
     * @param chunkIndex The index of the deleted chunk
     * @param chunkType Type of chunk: "table", "row", or "file"
     * @param parentTableIndex For rows, the table they belonged to (0 for tables/files)
     */
    public void addToFreeChunks(int chunkIndex, String chunkType, int parentTableIndex) {
        // Don't add freeChunks table operations to avoid recursion
        if (parentTableIndex != 0) {
            String parentTableMetadata = worker.readChunk(0, parentTableIndex + indexOffset, false, 1);
            if (DataUtilities.isValidTableMetadata(parentTableMetadata)) {
                String parentTableTitle = DataUtilities.parseTitle(parentTableMetadata);
                if ("freeChunks".equals(parentTableTitle)) {
                    logger.info("Skipping free chunk tracking for freeChunks table operations to avoid recursion");
                    return;
                }

                // CRITICAL FIX: Don't recycle chunks for the files table to avoid conflicts
                if ("files".equals(parentTableTitle)) {
                    logger.info("Skipping free chunk tracking for files table to avoid conflicts with file storage");
                    return;
                }
            }
        }

        ensureFreeChunksTableExists();

        String freeChunkData = "{\"chunkIndex\":" + chunkIndex +
                ",\"chunkType\":\"" + chunkType + "\"" +
                ",\"parentTableIndex\":" + parentTableIndex +
                ",\"coordinateSpace\":\"" + getCoordinateSpace(chunkType, parentTableIndex) + "\"}";

        MethodResponse result = insertRowWithoutRecycling("freeChunks", freeChunkData);
        if (result.hasError()) {
            logger.warning("Failed to add chunk " + chunkIndex + " to free chunks: " + result.getStatusMessage());
        } else {
            logger.info("Added chunk " + chunkIndex + " (" + chunkType + ") to free chunks list");
        }
    }

    /**
     * Gets the coordinate space identifier for a chunk type
     */
    private String getCoordinateSpace(String chunkType, int parentTableIndex) {
        switch (chunkType) {
            case "file":
                return "file_negative_z";
            case "table":
                return "table_positive_z";
            case "row":
                return "row_table_" + parentTableIndex;
            default:
                return "unknown";
        }
    }

    /**
     * Insert a row without using recycling (used for freeChunks table)
     */
    private MethodResponse insertRowWithoutRecycling(String tableTitle, String rowData) {
        if (rowData.isEmpty()) {
            return new MethodResponse(400, "Bad Request: Body of request is length 0. Your row needs content!", null, true);
        }

        if (tableTitle.isEmpty()) {
            return new MethodResponse(400, "Bad Request: Table title parameter is length 0. Your row needs a table to go to!", null, true);
        }

        int tableIndex = worker.getTableIndex(tableTitle, indexOffset);

        if (tableIndex == 0) {
            return new MethodResponse(400, "Bad Request: No table by the title of " + tableTitle + " exists!", null, true);
        }

        int index = getNextRowIndexSequential(tableIndex);
        int last = index - 1;

        String rowDataWithId = DataUtilities.addValueToJSON(index, "id", rowData);
        String newRowData = DataUtilities.rowBuilder(last, 0, rowDataWithId);

        boolean rowWriteResult = worker.writeToChunk(newRowData, index + indexOffset, tableIndex + indexOffset, false, 1);

        if (rowWriteResult) {
            updateLastRowMetadata(index, tableIndex);
            return new MethodResponse(200, "Wrote row " + rowDataWithId + " successfully!", rowDataWithId, false);
        } else {
            return new MethodResponse(500, "Internal Server Error: Failed to write row, is the data too large?", null, true);
        }
    }

    /**
     * Gets next row index sequentially without recycling
     */
    private int getNextRowIndexSequential(int tableIndex) {
        String startIndexText = worker.readChunk(1, tableIndex + 1, false, 1);

        if (startIndexText.isEmpty() || startIndexText.equals("0")) {
            worker.writeToChunk("1", 1, tableIndex + 1, false, 1);
            return 1;
        } else {
            int currentIndex = Integer.parseInt(startIndexText);
            String currentData = worker.readChunk(currentIndex + indexOffset, tableIndex + indexOffset, false, 1);
            int nextIndex = DataUtilities.parseNextIndexRow(currentData);

            while (nextIndex != 0) {
                currentIndex = nextIndex;
                currentData = worker.readChunk(currentIndex + indexOffset, tableIndex + indexOffset, false, 1);
                nextIndex = DataUtilities.parseNextIndexRow(currentData);
            }

            return currentIndex + 1;
        }
    }

    /**
     * Gets and removes a free chunk of the specified type
     * @param chunkType The type of chunk needed: "table", "row", or "file"
     * @param parentTableIndex For rows, the table index they should belong to (0 for tables/files)
     * @return The recycled chunk index, or 0 if no free chunks available
     */
    public int getFreeChunk(String chunkType, int parentTableIndex) {
        // Check if freeChunks table exists first, if not, return 0 (no free chunks)
        int freeChunksIndex = worker.getTableIndex("freeChunks", indexOffset);
        if (freeChunksIndex == 0) {
            return 0;
        }

        // Don't recycle chunks for the freeChunks table itself to avoid recursion
        if (parentTableIndex != 0) {
            String parentTableMetadata = worker.readChunk(0, parentTableIndex + indexOffset, false, 1);
            if (DataUtilities.isValidTableMetadata(parentTableMetadata)) {
                String parentTableTitle = DataUtilities.parseTitle(parentTableMetadata);
                if ("freeChunks".equals(parentTableTitle)) {
                    return 0;
                }

                // CRITICAL FIX: Don't recycle chunks for the files table to avoid conflicts
                if ("files".equals(parentTableTitle)) {
                    logger.info("No chunk recycling for files table to avoid conflicts");
                    return 0;
                }
            }
        }

        try {
            String targetCoordinateSpace = getCoordinateSpace(chunkType, parentTableIndex);
            String searchCondition = gatherRowsWithCondition(freeChunksIndex, "coordinateSpace", targetCoordinateSpace);
            JsonArray freeChunks = JsonParser.parseString(searchCondition).getAsJsonArray();

            if (freeChunks.size() > 0) {
                JsonObject chunk = freeChunks.get(0).getAsJsonObject();
                int chunkIndex = chunk.get("chunkIndex").getAsInt();
                int chunkId = chunk.get("id").getAsInt();

                // Remove this chunk from the free list
                deleteRowWithoutRecycling(freeChunksIndex, chunkId);

                logger.info("Recycling chunk " + chunkIndex + " for " + chunkType + " in coordinate space " + targetCoordinateSpace);
                return chunkIndex;
            }
        } catch (Exception e) {
            logger.warning("Error processing free chunks: " + e.getMessage());
        }

        return 0; // No suitable free chunk found
    }

    /**
     * Deletes a row without using recycling (used for freeChunks table management)
     */
    private MethodResponse deleteRowWithoutRecycling(int tableIndex, int rowIndex) {
        if (rowIndex <= 0) {
            return new MethodResponse(400, "Bad Request: Row indexes are 1-based", null, true);
        }

        if (tableIndex == 0) {
            return new MethodResponse(400, "Bad Request: Table doesn't exist or has corrupted metadata!", null, true);
        }

        String rowData = worker.readChunk(rowIndex + indexOffset, tableIndex + indexOffset, false, 1);

        if (rowData.isEmpty()) {
            return new MethodResponse(400, "Bad Request: Row doesn't exist or has corrupted metadata!", null, true);
        }

        int lastIndex = DataUtilities.parseLastIndexRow(rowData);
        int nextIndex = DataUtilities.parseNextIndexRow(rowData);

        // Update linked list structure
        if (lastIndex == 0) {
            worker.deleteChunk(1, tableIndex + indexOffset, false, 1);
            worker.writeToChunk("" + nextIndex, 1, tableIndex + indexOffset, false, 1);
        } else {
            String lastRowData = worker.readChunk(lastIndex + indexOffset, tableIndex + indexOffset, false, 1);
            String lastContent = DataUtilities.parseRowContent(lastRowData);
            int lastLast = DataUtilities.parseLastIndexRow(lastRowData);

            String newMeta = DataUtilities.rowBuilder(lastLast, nextIndex, lastContent);

            worker.deleteChunk(lastIndex + indexOffset, tableIndex + indexOffset, false, 1);
            worker.writeToChunk(newMeta, lastIndex + indexOffset, tableIndex + indexOffset, false, 1);
        }

        if (nextIndex != 0) {
            String nextMeta = worker.readChunk(nextIndex + indexOffset, tableIndex + indexOffset, false, 1);
            String nextContent = DataUtilities.parseRowContent(nextMeta);
            int nextNext = DataUtilities.parseNextIndexRow(nextMeta);

            String newMeta = DataUtilities.rowBuilder(lastIndex, nextNext, nextContent);

            worker.deleteChunk(nextIndex + indexOffset, tableIndex + indexOffset, false, 1);
            worker.writeToChunk(newMeta, nextIndex + indexOffset, tableIndex + indexOffset, false, 1);
        }

        // Delete target
        worker.deleteChunk(rowIndex + indexOffset, tableIndex + indexOffset, false, 1);

        return new MethodResponse(200, "Deleted row from table with success!", "Deleted row from table with success!", false);
    }

    // ==================== INSERT VALUE methods ====================

    public MethodResponse insertRow(String tableTitle, String rowData) {
        if (rowData.isEmpty()) {
            return new MethodResponse(400, "Bad Request: Body of request is length 0. Your row needs content!", null, true);
        }

        if (tableTitle.isEmpty()) {
            return new MethodResponse(400, "Bad Request: Table title parameter is length 0. Your row needs a table to go to!", null, true);
        }

        logger.info("Successfully received row insert request");
        logger.info("Table Title: " + tableTitle);
        logger.info("Row Data: " + rowData);

        int tableIndex = worker.getTableIndex(tableTitle, indexOffset);

        if (tableIndex == 0) {
            return new MethodResponse(400, "Bad Request: No table by the title of " + tableTitle + " exists!", null, true);
        }

        // Use special handling for freeChunks table to avoid recursion
        if ("freeChunks".equals(tableTitle)) {
            return insertRowWithoutRecycling(tableTitle, rowData);
        }

        int index = getNextRowIndex(tableIndex, tableTitle);
        int last = index - 1;

        String rowDataWithId = DataUtilities.addValueToJSON(index, "id", rowData);
        String newRowData = DataUtilities.rowBuilder(last, 0, rowDataWithId);

        boolean rowWriteResult = worker.writeToChunk(newRowData, index + indexOffset, tableIndex + indexOffset, false, 1);

        if (rowWriteResult) {
            updateLastRowMetadata(index, tableIndex);
            return new MethodResponse(200, "Wrote row " + rowDataWithId + " successfully!", rowDataWithId, false);
        } else {
            return new MethodResponse(500, "Internal Server Error: Failed to write row, is the data too large?", null, true);
        }
    }

    public int getNextRowIndex(int tableIndex, String tableTitle) {
        // CRITICAL FIX: Don't recycle chunks for the files table
        if (!"files".equals(tableTitle)) {
            // First, try to get a recycled chunk for non-files tables
            int freeChunk = getFreeChunk("row", tableIndex);
            if (freeChunk > 0) {
                return freeChunk;
            }
        }

        // No free chunks available or files table, use sequential allocation
        return getNextRowIndexSequential(tableIndex);
    }

    public void updateLastRowMetadata(int index, int tableIndex) {
        if (index != 1) {
            String metadata = worker.readChunk((index - 1) + indexOffset, tableIndex + indexOffset, false, 1);

            int last = DataUtilities.parseLastIndexRow(metadata);
            String rowContent = DataUtilities.parseRowContent(metadata);

            worker.deleteChunk((index - 1) + indexOffset, tableIndex + indexOffset, false, 1);

            String newMetadata = DataUtilities.rowBuilder(last, index, rowContent);

            worker.writeToChunk(newMetadata, (index - 1) + indexOffset, tableIndex + indexOffset, false, 1);
        }
    }

    // ==================== UPDATE methods ====================

    public MethodResponse updateRow(String tableTitle, int rowId, String content) {
        if (content.isEmpty()) {
            return new MethodResponse(400, "Bad Request: No new data provided!", null, true);
        }

        int tableIndex = worker.getTableIndex(tableTitle, indexOffset);

        if (tableIndex == 0) {
            return new MethodResponse(400, "Bad Request: Table doesn't exist or has corrupted metadata!", null, true);
        }

        String currentData = worker.readChunk(rowId + indexOffset, tableIndex + indexOffset, false, 1);

        if (currentData.isEmpty()) {
            return new MethodResponse(400, "Bad Request: There is no id " + rowId + " in table " + tableTitle + "!", null, true);
        } else {
            int lastIndex = DataUtilities.parseLastIndexRow(currentData);
            int nextIndex = DataUtilities.parseNextIndexRow(currentData);

            String newContent = DataUtilities.rowBuilder(lastIndex, nextIndex, content);

            worker.deleteChunk(rowId + indexOffset, tableIndex + indexOffset, false, 1);
            worker.writeToChunk(newContent, rowId + indexOffset, tableIndex + indexOffset, false, 1);

            return new MethodResponse(200, "Successfully updated id " + rowId + " in " + tableTitle, "Successfully updated id " + rowId + " in " + tableTitle, false);
        }
    }

    // ==================== SELECT * methods ====================

    public MethodResponse readTable(String tableTitle) {
        if (tableTitle.isEmpty()) {
            return new MethodResponse(400, "Bad Request: No table title provided!", null, true);
        }

        int tableIndex = worker.getTableIndex(tableTitle, indexOffset);

        if (tableIndex == 0) {
            return new MethodResponse(400, "Bad Request: Table doesn't exist or has corrupted metadata!", null, true);
        }

        String result = readAllRows(tableIndex);

        return new MethodResponse(200, "Successfully read all rows from " + tableTitle + "!", result, false);
    }

    public MethodResponse readTableWithCondition(String tableTitle, String key, String target) {
        if (tableTitle.isEmpty()) {
            return new MethodResponse(400, "Bad Request: No title table provided!", null, true);
        }

        int tableIndex = worker.getTableIndex(tableTitle, indexOffset);

        if (tableIndex == 0) {
            return new MethodResponse(400, "Bad Request: Table doesn't exist or has corrupted metadata!", null, true);
        }

        String result = gatherRowsWithCondition(tableIndex, key, target);

        return new MethodResponse(200, "Successfully read rows where " + key + " == " + target, result, false);
    }

    public String gatherRowsWithCondition(int tableIndex, String key, String target) {
        StringBuilder jsonArrayBuilder = new StringBuilder("[");

        String tableStartIndex = worker.readChunk(1, tableIndex + indexOffset, false, 1);

        if (tableStartIndex.isEmpty() || tableStartIndex.equals("0")) {
            jsonArrayBuilder.append("]");
            return jsonArrayBuilder.toString();
        }

        int currentIndex = Integer.parseInt(tableStartIndex);
        String currentRow = worker.readChunk(currentIndex + 1, tableIndex + 1, false, 1);
        String content = DataUtilities.parseRowContent(currentRow);
        int nextIndex = DataUtilities.parseNextIndexRow(currentRow);

        if (DataUtilities.meetsCondition(content, key, target)) {
            if (nextIndex == 0) {
                jsonArrayBuilder.append(content);
            } else {
                jsonArrayBuilder.append(content);
                jsonArrayBuilder.append(",");
            }
        }

        while (nextIndex != 0) {
            currentIndex = nextIndex;
            currentRow = worker.readChunk(currentIndex + 1, tableIndex + 1, false, 1);
            content = DataUtilities.parseRowContent(currentRow);
            nextIndex = DataUtilities.parseNextIndexRow(currentRow);
            boolean matches = DataUtilities.meetsCondition(content, key, target);

            if (matches) {
                if (nextIndex == 0) {
                    jsonArrayBuilder.append(content);
                } else {
                    jsonArrayBuilder.append(content);
                    jsonArrayBuilder.append(",");
                }
            }
        }

        // triple check for the damn mysterious leading comma
        if (jsonArrayBuilder.charAt(jsonArrayBuilder.length() - 1) == ',') {
            jsonArrayBuilder.deleteCharAt(jsonArrayBuilder.length() - 1);
        }
        jsonArrayBuilder.append("]");

        return jsonArrayBuilder.toString();
    }

    public String readAllRows(int tableIndex) {
        StringBuilder jsonArrayBuilder = new StringBuilder("[");

        String tableStartIndex = worker.readChunk(1, tableIndex + indexOffset, false, 1);

        if (tableStartIndex.isEmpty() || tableStartIndex.equals("0")) {
            jsonArrayBuilder.append("]");
            return jsonArrayBuilder.toString();
        }

        int currentIndex = Integer.parseInt(tableStartIndex);
        String currentRow = worker.readChunk(currentIndex + indexOffset, tableIndex + indexOffset, false, 1);
        String content = DataUtilities.parseRowContent(currentRow);
        int nextIndex = DataUtilities.parseNextIndexRow(currentRow);

        if (nextIndex == 0) {
            jsonArrayBuilder.append(content);
        } else {
            jsonArrayBuilder.append(content);
            jsonArrayBuilder.append(",");
        }

        while (nextIndex != 0) {
            currentIndex = nextIndex;
            currentRow = worker.readChunk(currentIndex + indexOffset, tableIndex + indexOffset, false, 1);
            content = DataUtilities.parseRowContent(currentRow);
            nextIndex = DataUtilities.parseNextIndexRow(currentRow);

            jsonArrayBuilder.append(content);
            if (nextIndex != 0)
                jsonArrayBuilder.append(",");
        }

        jsonArrayBuilder.append("]");

        return jsonArrayBuilder.toString();
    }

    // ==================== SELECT {id} methods ====================

    public MethodResponse readRow(String tableTitle, int rowIndex) {
        if (rowIndex <= 0) {
            return new MethodResponse(400, "Bad Request: Row indexes are 1-based. Received id " + rowIndex + "!", null, true);
        }

        if (tableTitle.isEmpty()) {
            return new MethodResponse(400, "Bad Request: No table title provided!", null, true);
        }

        int tableIndex = worker.getTableIndex(tableTitle, indexOffset);

        if (tableIndex == 0) {
            return new MethodResponse(400, "Bad Request: Table doesn't exist or has corrupted metadata!", null, true);
        }

        String rowData = worker.readChunk(rowIndex + indexOffset, tableIndex + indexOffset, false, 1);

        if (rowData.isEmpty()) {
            return new MethodResponse(400, "Bad Request: Row doesn't exist or has corrupted metadata!", null, true);
        }

        String content = DataUtilities.parseRowContent(rowData);

        return new MethodResponse(200, "Successfully read from index " + rowIndex + " in " + tableTitle + "!", content, false);
    }

    // ==================== CREATE TABLE methods ====================

    public MethodResponse createTable(String tableTitle) {
        if (tableTitle.isEmpty()) {
            return new MethodResponse(400, "Bad Request: Body of request is length 0. Your table needs a title!", null, true);
        }

        if (worker.getTableIndex(tableTitle, indexOffset) != 0) {
            return new MethodResponse(400, "Bad Request: A table with that name already exists!", null, true);
        }

        logger.info("Successfully received table create request");
        logger.info("Table Title: " + tableTitle);

        int index = getNextTableIndex();
        int last = index - 1;

        String newFileMetadata = DataUtilities.tableMetadataBuilder(tableTitle, last, 0);

        boolean metadataWriteResult = worker.writeToChunk(newFileMetadata, 0, index + indexOffset, false, 1);

        if (metadataWriteResult) {
            updateLastTableMetadata(index);
            placeTableSign(tableTitle, index);

            return new MethodResponse(200, "Wrote table " + tableTitle + " successfully!", "Wrote table " + tableTitle + " successfully!", false);
        } else {
            return new MethodResponse(400, "Bad Request: Failed to write table metadata!", null, true);
        }
    }

    public int getNextTableIndex() {
        // First, try to get a recycled chunk
        int freeChunk = getFreeChunk("table", 0);
        if (freeChunk > 0) {
            return freeChunk;
        }

        // No free chunks available, use existing sequential allocation logic
        return getNextTableIndexSequential();
    }

    public void placeTableSign(String fileTitle, int fileIndex) {
        Block block = world.getBlockAt(-1, -63, (fileIndex * 16) + (indexOffset * 16) - 1);

        block.setType(Material.OAK_SIGN);

        Sign sign = (Sign) block.getState();

        sign.setLine(1, fileTitle);

        sign.update();
    }

    public void updateLastTableMetadata(int index) {
        if (index != 1) {
            String metadata = worker.readChunk(0, (index - 1) + indexOffset, false, 1);

            String title = DataUtilities.parseTitle(metadata);
            int last = DataUtilities.parseLastIndexTable(metadata);

            worker.deleteChunk(0, (index - 1) + indexOffset, false, 1);

            String newMetadata = DataUtilities.tableMetadataBuilder(title, last, index);

            worker.writeToChunk(newMetadata, 0, (index - 1) + indexOffset, false, 1);
        }
    }

    // ==================== DELETE TABLE methods ====================

    public MethodResponse deleteTable(String tableTitle) {
        int tableIndex = worker.getTableIndex(tableTitle, indexOffset);

        if (tableIndex == 0) {
            return new MethodResponse(400, "Bad Request: No table with title " + tableTitle + " exists!", "", true);
        } else {
            return deleteTable(tableIndex);
        }
    }

    public MethodResponse deleteTable(int index) {
        if (index <= 0) {
            return new MethodResponse(400, "Bad Request: Table indexes are 1-based", "", true);
        }

        String metadata = worker.readChunk(0, index + indexOffset, false, 1);

        if (!DataUtilities.isValidTableMetadata(metadata)) {
            return new MethodResponse(400, "Bad Request: Table doesn't exist or has corrupted metadata", "", true);
        }

        String targetTitle = DataUtilities.parseTitle(metadata);
        int lastIndex = DataUtilities.parseLastIndexTable(metadata);
        int nextIndex = DataUtilities.parseNextIndexTable(metadata);

        logger.info("target table title: " + targetTitle);
        logger.info("target table lastIndex: " + lastIndex);
        logger.info("target table nextIndex: " + nextIndex);

        // if target table has no last index, update start index to be target's next index
        if (lastIndex == 0) {
            worker.writeToChunk("" + nextIndex, 0, 1, false, 1);
        } else {
            // otherwise, update nextIndex of target's last to be target's nextIndex
            String lastMeta = worker.readChunk(0, lastIndex + indexOffset, false, 1);
            String lastTitle = DataUtilities.parseTitle(lastMeta);
            int lastLast = DataUtilities.parseLastIndexTable(lastMeta);

            String newMeta = DataUtilities.tableMetadataBuilder(lastTitle, lastLast, nextIndex);

            logger.info("Updating metadata for previous table in the chain, setting nextIndex to " + nextIndex);

            worker.deleteChunk(0, lastIndex + indexOffset, false, 1);
            worker.writeToChunk(newMeta, 0, lastIndex + indexOffset, false, 1);
        }

        // if target table has a nextIndex, update nextIndex's last to be target's last
        if (nextIndex != 0) {
            String nextMeta = worker.readChunk(0, nextIndex + indexOffset, false, 1);
            String nextTitle = DataUtilities.parseTitle(nextMeta);
            int nextNext = DataUtilities.parseNextIndexTable(nextMeta);

            logger.info("Updating metadata for next table in the chain, setting lastIndex to " + lastIndex);

            String newMeta = DataUtilities.tableMetadataBuilder(nextTitle, lastIndex, nextNext);

            worker.deleteChunk(0, nextIndex + indexOffset, false, 1);
            worker.writeToChunk(newMeta, 0, nextIndex + indexOffset, false, 1);
        }

        deleteTableSign(index);

        int rowsDeleted = deleteAllRows(index);

        // delete target's startIndex
        worker.deleteChunk(1, index + indexOffset, false, 1);
        // delete target's metadata
        worker.deleteChunk(0, index + indexOffset, false, 1);

        // Add the deleted table chunks to free chunks for recycling
        addToFreeChunks(index, "table", 0);

        return new MethodResponse(200, "Deleted table " + targetTitle + " with success! Rows deleted: " + rowsDeleted, "Deleted table " + targetTitle + " with success! Rows deleted: " + rowsDeleted, false);
    }

    public int deleteAllRows(int tableIndex) {
        String tableStartIndex = worker.readChunk(1, tableIndex + indexOffset, false, 1);

        if (tableStartIndex.isEmpty()) {
            return 0;
        }

        if (tableStartIndex.equals("0")) {
            worker.deleteChunk(1, tableIndex + indexOffset, false, 1);
            return 0;
        }

        int counter = 1;

        int currentIndex = Integer.parseInt(tableStartIndex);
        String currentRow = worker.readChunk(currentIndex + indexOffset, tableIndex + indexOffset, false, 1);
        int nextIndex = DataUtilities.parseNextIndexRow(currentRow);
        worker.deleteChunk(currentIndex + indexOffset, tableIndex + indexOffset, false, 1);

        // Add deleted row to free chunks
        addToFreeChunks(currentIndex, "row", tableIndex);

        while (nextIndex != 0) {
            currentIndex = nextIndex;
            currentRow = worker.readChunk(currentIndex + 1, tableIndex + indexOffset, false, 1);
            nextIndex = DataUtilities.parseNextIndexRow(currentRow);
            worker.deleteChunk(currentIndex + indexOffset, tableIndex + indexOffset, false, 1);

            // Add deleted row to free chunks
            addToFreeChunks(currentIndex, "row", tableIndex);

            counter++;
        }

        worker.deleteChunk(currentIndex + indexOffset, tableIndex + indexOffset, false, 1);

        worker.writeToChunk("0", 1, tableIndex + indexOffset, false, 1);

        return counter;
    }

    public int deleteAllRowsWithCondition(int tableIndex, String key, String target) {
        String tableStartIndex = worker.readChunk(1, tableIndex + indexOffset, false, 1);

        if (tableStartIndex.isEmpty()) {
            return 0;
        }

        if (tableStartIndex.equals("0")) {
            worker.deleteChunk(1, tableIndex + indexOffset, false, 1);
            return 0;
        }

        int counter = 1;

        int currentIndex = Integer.parseInt(tableStartIndex);
        String currentRow = worker.readChunk(currentIndex + indexOffset, tableIndex + indexOffset, false, 1);
        int nextIndex = DataUtilities.parseNextIndexRow(currentRow);

        String currentContent = DataUtilities.parseRowContent(currentRow);
        if (DataUtilities.meetsCondition(currentContent, key, target)) {
            deleteRow(tableIndex, currentIndex);
        }

        while (nextIndex != 0) {
            currentIndex = nextIndex;
            currentRow = worker.readChunk(currentIndex + 1, tableIndex + indexOffset, false, 1);
            nextIndex = DataUtilities.parseNextIndexRow(currentRow);

            currentContent = DataUtilities.parseRowContent(currentRow);
            if (DataUtilities.meetsCondition(currentContent, key, target)) {
                deleteRow(tableIndex, currentIndex);
            }

            counter++;
        }

        worker.deleteChunk(currentIndex + indexOffset, tableIndex + indexOffset, false, 1);

        worker.writeToChunk("0", 1, tableIndex + indexOffset, false, 1);

        return counter;
    }

    public void deleteTableSign(int fileIndex) {
        world.getBlockAt(-1, -63, (fileIndex * 16) + (indexOffset * 16) - 1).setType(Material.AIR);
    }

    // ==================== DELETE * FROM methods ====================

    public MethodResponse deleteAllFromTable(String tableTitle) {
        int tableIndex = worker.getTableIndex(tableTitle, indexOffset);

        if (tableIndex == 0) {
            return new MethodResponse(400, "Bad Request: No table with title " + tableTitle + " exists!", null, true);
        }

        int rowsDeleted = deleteAllRows(tableIndex);

        return new MethodResponse(200, "Successfully deleted all rows from " + tableTitle + "! Rows deleted: " + rowsDeleted, "Successfully deleted all rows from " + tableTitle + "! Rows deleted: " + rowsDeleted, false);
    }

    public MethodResponse deleteAllFromTableWithCondition(String tableTitle, String key, String target) {
        int tableIndex = worker.getTableIndex(tableTitle, indexOffset);

        if (tableIndex == 0) {
            return new MethodResponse(400, "Bad Request: No table with title " + tableTitle + " exists!", null, true);
        }

        int rowsDeleted = deleteAllRowsWithCondition(tableIndex, key, target);

        return new MethodResponse(200, "Successfully deleted all rows from " + tableTitle + "! Rows deleted: " + rowsDeleted, "Successfully deleted all rows from " + tableTitle + "! Rows deleted: " + rowsDeleted, false);
    }

    // ==================== DELETE {id} methods ====================

    public MethodResponse deleteRow(String tableTitle, int rowIndex) {
        if (rowIndex <= 0) {
            return new MethodResponse(400, "Bad Request: Row indexes are 1-based", null, true);
        }

        if (tableTitle.isEmpty()) {
            return new MethodResponse(400, "Bad Request: No table name provided!", null, true);
        }

        int tableIndex = worker.getTableIndex(tableTitle, indexOffset);

        if (tableIndex == 0) {
            return new MethodResponse(400, "Bad Request: Table doesn't exist or has corrupted metadata!", null, true);
        }

        return deleteRow(tableIndex, rowIndex);
    }

    public MethodResponse deleteRow(int tableIndex, int rowIndex) {
        if (rowIndex <= 0) {
            return new MethodResponse(400, "Bad Request: Row indexes are 1-based", null, true);
        }

        if (tableIndex == 0) {
            return new MethodResponse(400, "Bad Request: Table doesn't exist or has corrupted metadata!", null, true);
        }

        // Check if this is a recyclable table
        String tableMetadata = worker.readChunk(0, tableIndex + indexOffset, false, 1);
        boolean isRecyclable = true;
        if (DataUtilities.isValidTableMetadata(tableMetadata)) {
            String tableTitle = DataUtilities.parseTitle(tableMetadata);
            if ("freeChunks".equals(tableTitle) || "files".equals(tableTitle)) {
                isRecyclable = false;
            }
        }

        String rowData = worker.readChunk(rowIndex + indexOffset, tableIndex + indexOffset, false, 1);

        if (rowData.isEmpty()) {
            return new MethodResponse(400, "Bad Request: Row doesn't exist or has corrupted metadata!", null, true);
        }

        int lastIndex = DataUtilities.parseLastIndexRow(rowData);
        int nextIndex = DataUtilities.parseNextIndexRow(rowData);

        logger.info("target row lastIndex: " + lastIndex);
        logger.info("target row nextIndex: " + nextIndex);

        // if target has no last index, update start index to be target's next index
        if (lastIndex == 0) {
            worker.deleteChunk(1, tableIndex + indexOffset, false, 1);
            worker.writeToChunk("" + nextIndex, 1, tableIndex + indexOffset, false, 1);
        } else {
            // otherwise, update nextIndex of target's last to be target's nextIndex
            String lastRowData = worker.readChunk(lastIndex + indexOffset, tableIndex + indexOffset, false, 1);
            String lastContent = DataUtilities.parseRowContent(lastRowData);
            int lastLast = DataUtilities.parseLastIndexRow(lastRowData);

            String newMeta = DataUtilities.rowBuilder(lastLast, nextIndex, lastContent);

            logger.info("Updating metadata for previous row in the chain, setting nextIndex to " + nextIndex);

            worker.deleteChunk(lastIndex + indexOffset, tableIndex + indexOffset, false, 1);
            worker.writeToChunk(newMeta, lastIndex + indexOffset, tableIndex + indexOffset, false, 1);
        }

        // if target table has a nextIndex, update nextIndex's last to be target's last
        if (nextIndex != 0) {
            String nextMeta = worker.readChunk(nextIndex + indexOffset, tableIndex + indexOffset, false, 1);
            String nextContent = DataUtilities.parseRowContent(nextMeta);
            int nextNext = DataUtilities.parseNextIndexRow(nextMeta);

            logger.info("Updating metadata for next row in the chain, setting lastIndex to " + lastIndex);

            String newMeta = DataUtilities.rowBuilder(lastIndex, nextNext, nextContent);

            worker.deleteChunk(nextIndex + indexOffset, tableIndex + indexOffset, false, 1);
            worker.writeToChunk(newMeta, nextIndex + indexOffset, tableIndex + indexOffset, false, 1);
        }
        // delete target
        worker.deleteChunk(rowIndex + indexOffset, tableIndex + indexOffset, false, 1);

        // Add the deleted row chunk to free chunks for recycling (if recyclable)
        if (isRecyclable) {
            addToFreeChunks(rowIndex, "row", tableIndex);
        }

        return new MethodResponse(200, "Deleted row from table with success!", "Deleted row from table with success!", false);
    }

    // ==================== PROTECT methods ====================

    public MethodResponse protectTable(String tableTitle, String protection) {
        if (tableTitle.isEmpty()) {
            return new MethodResponse(400, "Bad Request: Table title is empty!", null, true);
        }

        if (protection.isEmpty()) {
            return new MethodResponse(400, "Bad Request: Protection value is empty!", null, true);
        }

        // Check protection flags are valid
        MethodResponse checkFlags = DataUtilities.areValidProtectionFlags(protection);
        if (checkFlags.hasError()) {
            return checkFlags;
        } else {
            int tableIndex = worker.getTableIndex(tableTitle, indexOffset);

            if (tableIndex == 0) {
                return new MethodResponse(400, "Bad Request: Table doesn't exist or has corrupted metadata!", null, true);
            }

            String tableMetadata = worker.readChunk(0, tableIndex + indexOffset, false, 1);

            if (DataUtilities.isValidTableMetadata(tableMetadata)) {
                // table metadata is valid, construct and overwrite current data.

                String protectionField = DataUtilities.tableProtectionBuilder(protection);
                String newMetadata = DataUtilities.generateProtectedMetadata(tableMetadata, protectionField);

                worker.deleteChunk(0, tableIndex + indexOffset, false, 1);
                worker.writeToChunk(newMetadata, 0, tableIndex + indexOffset, false, 1);

                return new MethodResponse(200, "Successfully updated table protection rules", "Successfully updated table protection rules", false);
            } else {
                // table metadata is corrupt, return failure
                return new MethodResponse(500, "Internal Server Error: Metadata for table " + tableTitle + " is corrupt. Metadata: " + tableMetadata, null, true);
            }
        }
    }

    public MethodResponse removeProtections(String tableTitle) {
        if (tableTitle.isEmpty()) {
            return new MethodResponse(400, "Bad Request: Table title is empty!", null, true);
        }
        int tableIndex = worker.getTableIndex(tableTitle, indexOffset);

        if (tableIndex == 0) {
            return new MethodResponse(400, "Bad Request: Table doesn't exist or has corrupted metadata!", null, true);
        }

        String tableMetadata = worker.readChunk(0, tableIndex + indexOffset, false, 1);

        if (DataUtilities.isValidTableMetadata(tableMetadata)) {
            // table metadata is valid, construct and overwrite current data.

            int last = DataUtilities.parseLastIndexTable(tableMetadata);
            int next = DataUtilities.parseNextIndexTable(tableMetadata);
            String title = DataUtilities.parseTitle(tableMetadata);

            String newMetadata = DataUtilities.tableMetadataBuilder(title, last, next);

            worker.deleteChunk(0, tableIndex + indexOffset, false, 1);
            worker.writeToChunk(newMetadata, 0, tableIndex + indexOffset, false, 1);

            return new MethodResponse(200, "Successfully updated table protection rules", "Successfully updated table protection rules", false);
        } else {
            // table metadata is corrupt, return failure
            return new MethodResponse(500, "Internal Server Error: Metadata for table " + tableTitle + " is corrupt. Metadata: " + tableMetadata, null, true);
        }
    }

    public MethodResponse getProtectionFlags(String tableTitle) {
        if (tableTitle.isEmpty()) {
            return new MethodResponse(400, "Bad Request: Table title is empty!", null, true);
        }

        int tableIndex = worker.getTableIndex(tableTitle, indexOffset);

        if (tableIndex == 0) {
            return new MethodResponse(400, "Bad Request: Table doesn't exist or has corrupted metadata!", null, true);
        }

        String metadata = worker.readChunk(0, tableIndex + indexOffset, false, 1);

        if (DataUtilities.isValidTableMetadata(metadata)) {

            if (DataUtilities.tableHasProtectionFlags(metadata)) {
                String flags = DataUtilities.parseTableProtectionFlags(metadata);

                if (DataUtilities.areValidProtectionFlags(flags).hasError()) {
                    return new MethodResponse(500, "Internal Server Error: Protection flags on table " + tableTitle + " are invalid: " + flags, null, true);
                } else {
                    return new MethodResponse(200, flags, flags, false);
                }
            } else {
                return new MethodResponse(200, "", "", false);
            }
        } else {
            return new MethodResponse(500, "Internal Server Error: Metadata for table " + tableTitle + " is corrupt. Metadata: " + metadata, null, true);
        }
    }
}