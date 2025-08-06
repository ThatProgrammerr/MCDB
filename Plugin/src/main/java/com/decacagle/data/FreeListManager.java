package com.decacagle.data;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.World;

public class FreeListManager {

    private Logger logger;
    private World world;
    private DataWorker worker;

    // Special chunk coordinates for storing free lists
    private static final int FILE_FREE_LIST_X = -100;
    private static final int FILE_FREE_LIST_Z = -100;
    private static final int TABLE_FREE_LIST_X = -100;
    private static final int TABLE_FREE_LIST_Z = -101;
    private static final int ROW_FREE_LIST_X_BASE = -100;
    private static final int ROW_FREE_LIST_Z_BASE = -200;

    public FreeListManager(Logger logger, World world, DataWorker worker) {
        this.logger = logger;
        this.world = world;
        this.worker = worker;
    }

    // File free list methods
    public int getNextAvailableFileIndex() {
        String freeListData = worker.readChunk(FILE_FREE_LIST_X, FILE_FREE_LIST_Z, false, 1);

        if (!freeListData.isEmpty() && !freeListData.equals("0")) {
            List<Integer> freeIndexes = parseFreeList(freeListData);
            if (!freeIndexes.isEmpty()) {
                int recycledIndex = freeIndexes.remove(0);
                saveFreeList(freeIndexes, FILE_FREE_LIST_X, FILE_FREE_LIST_Z);
                logger.info("Recycling file index: " + recycledIndex);
                return recycledIndex;
            }
        }

        return -1; // No free indexes available
    }

    public void addFileIndexToFreeList(int index) {
        String freeListData = worker.readChunk(FILE_FREE_LIST_X, FILE_FREE_LIST_Z, false, 1);
        List<Integer> freeIndexes = freeListData.isEmpty() ? new ArrayList<>() : parseFreeList(freeListData);

        freeIndexes.add(index);
        saveFreeList(freeIndexes, FILE_FREE_LIST_X, FILE_FREE_LIST_Z);
        logger.info("Added file index to free list: " + index);
    }

    // Table free list methods
    public int getNextAvailableTableIndex() {
        String freeListData = worker.readChunk(TABLE_FREE_LIST_X, TABLE_FREE_LIST_Z, false, 1);

        if (!freeListData.isEmpty() && !freeListData.equals("0")) {
            List<Integer> freeIndexes = parseFreeList(freeListData);
            if (!freeIndexes.isEmpty()) {
                int recycledIndex = freeIndexes.remove(0);
                saveFreeList(freeIndexes, TABLE_FREE_LIST_X, TABLE_FREE_LIST_Z);
                logger.info("Recycling table index: " + recycledIndex);
                return recycledIndex;
            }
        }

        return -1; // No free indexes available
    }

    public void addTableIndexToFreeList(int index) {
        String freeListData = worker.readChunk(TABLE_FREE_LIST_X, TABLE_FREE_LIST_Z, false, 1);
        List<Integer> freeIndexes = freeListData.isEmpty() ? new ArrayList<>() : parseFreeList(freeListData);

        freeIndexes.add(index);
        saveFreeList(freeIndexes, TABLE_FREE_LIST_X, TABLE_FREE_LIST_Z);
        logger.info("Added table index to free list: " + index);
    }

    // Row free list methods (per table)
    public int getNextAvailableRowIndex(int tableIndex) {
        int freeListX = ROW_FREE_LIST_X_BASE;
        int freeListZ = ROW_FREE_LIST_Z_BASE - tableIndex;

        String freeListData = worker.readChunk(freeListX, freeListZ, false, 1);

        if (!freeListData.isEmpty() && !freeListData.equals("0")) {
            List<Integer> freeIndexes = parseFreeList(freeListData);
            if (!freeIndexes.isEmpty()) {
                int recycledIndex = freeIndexes.remove(0);
                saveFreeList(freeIndexes, freeListX, freeListZ);
                logger.info("Recycling row index " + recycledIndex + " for table " + tableIndex);
                return recycledIndex;
            }
        }

        return -1; // No free indexes available
    }

    public void addRowIndexToFreeList(int rowIndex, int tableIndex) {
        int freeListX = ROW_FREE_LIST_X_BASE;
        int freeListZ = ROW_FREE_LIST_Z_BASE - tableIndex;

        String freeListData = worker.readChunk(freeListX, freeListZ, false, 1);
        List<Integer> freeIndexes = freeListData.isEmpty() ? new ArrayList<>() : parseFreeList(freeListData);

        freeIndexes.add(rowIndex);
        saveFreeList(freeIndexes, freeListX, freeListZ);
        logger.info("Added row index " + rowIndex + " to free list for table " + tableIndex);
    }

    public void clearRowFreeListForTable(int tableIndex) {
        int freeListX = ROW_FREE_LIST_X_BASE;
        int freeListZ = ROW_FREE_LIST_Z_BASE - tableIndex;
        worker.deleteChunk(freeListX, freeListZ, false, 1);
    }

    // Helper methods
    private List<Integer> parseFreeList(String data) {
        List<Integer> indexes = new ArrayList<>();
        if (data != null && !data.trim().isEmpty()) {
            String[] parts = data.split(",");
            for (String part : parts) {
                try {
                    int index = Integer.parseInt(part.trim());
                    if (index > 0) {
                        indexes.add(index);
                    }
                } catch (NumberFormatException e) {
                    logger.warning("Invalid free list entry: " + part);
                }
            }
        }
        return indexes;
    }

    private void saveFreeList(List<Integer> freeIndexes, int x, int z) {
        if (freeIndexes.isEmpty()) {
            worker.deleteChunk(x, z, false, 1);
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < freeIndexes.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(freeIndexes.get(i));
            }
            worker.deleteChunk(x, z, false, 1);
            worker.writeToChunk(sb.toString(), x, z, false, 1);
        }
    }
}