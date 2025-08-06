package com.decacagle.data;

import com.decacagle.DecaDB;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.logging.Logger;

import static com.decacagle.data.DataUtilities.*;

public class DataWorker {

    private Logger logger;
    private World world;
    private DecaDB plugin;

    public DataWorker(Logger logger, World world, DecaDB plugin) {
        this.logger = logger;
        this.world = world;
        this.plugin = plugin;
    }

    public boolean writeToChunk(String body, int xIndex, int zIndex, boolean writeInfinitely, int direction) {

        int indexX = xIndex * 16;
        int indexZ = -1 + (zIndex * 16);

        // Build hex string from ASCII
        StringBuilder res = new StringBuilder();

        for (int i = 0; i < body.length(); i++) {
            res.append(asciiToHex(body.charAt(i)));
        }

        // Begin writing hex to chunk

        int x = indexX;
        int z = indexZ;
        int y = -64;

        for (int i = 0; i < res.length(); i++) {
            Block current = world.getBlockAt(x, y, z);

            Material targetMat = getCorrespondingBlock(res.charAt(i));
            current.setType(targetMat);

            x++;

            if (x >= (indexX + 16)) {
                if (z <= indexZ - 15) {
                    x = indexX;
                    z = indexZ;
                    y++;
                } else {
                    x = indexX;
                    z--;
                }
            }

            if (y >= 320) {
                if (writeInfinitely) {
                    xIndex += direction;
                    indexX = xIndex * 16;
                    x = indexX;
                    z = indexZ;
                    y = -64;
                } else {
                    logger.info("Ran out of build height, discontinuing write!");
                    return false;
                }
            }

        }

        return true;

    }

    public String readChunk(int xIndex, int zIndex, boolean readInfinitely, int direction) {
        // Calculate starting scan coordinates based on given index

        int startX = xIndex * 16;
        int startZ = -1 + (zIndex * 16);

        int x = startX;
        int z = startZ;
        int y = -64;

        // Scan through chunk at index and build hex string
        StringBuilder hexBuilder = new StringBuilder();
        boolean reading = true;

        while (reading) {
            Block current = world.getBlockAt(x, y, z);
            char presentChar = getCorrespondingChar(current.getType());
            if (presentChar != 'n') {
                hexBuilder.append(presentChar);
            } else {
                // Found invalid Material, chunk is out of data, stop scanning
                reading = false;
            }

            x++;

            if (x >= (startX + 16)) {
                if (z <= startZ - 15) {
                    x = startX;
                    z = startZ;
                    y++;
                } else {
                    x = startX;
                    z--;
                }
            }

            if (y >= 320) {
                if (readInfinitely) {
                    xIndex += direction;
                    startX = xIndex * 16;
                    x = startX;
                    z = startZ;
                    y = -64;
                } else {
                    logger.info("Ran out of build height, discontinuing read!");
                    return "Failed";
                }
            }

        }

        // Take constructed hex line and build ascii result

        StringBuilder asciiBuilder = new StringBuilder();

        for (int i = 0; i < hexBuilder.length(); i += 2) {

            String hexByte = new String(new char[]{hexBuilder.charAt(i), hexBuilder.charAt(i + 1)});

            asciiBuilder.append(hexToAscii(hexByte));

        }

        return asciiBuilder.toString();

    }

    public String readChunkSafely(int xIndex, int zIndex, boolean readInfinitely, int direction) {
        int startX = xIndex * 16;
        int startZ = -1 + (zIndex * 16);

        int x = startX;
        int z = startZ;
        int y = -64;

        StringBuilder hexBuilder = new StringBuilder();
        boolean reading = true;
        int blocksRead = 0;
        final int MAX_BLOCKS_PER_CHUNK = 16 * 16 * 384; // Reasonable limit

        while (reading && blocksRead < MAX_BLOCKS_PER_CHUNK) {
            Block current = world.getBlockAt(x, y, z);
            char presentChar = getCorrespondingChar(current.getType());

            if (presentChar != 'n') {
                hexBuilder.append(presentChar);
                blocksRead++;
            } else {
                // Found invalid Material, chunk is out of data, stop scanning
                reading = false;
                break;
            }

            // Move to next position
            x++;
            if (x >= (startX + 16)) {
                if (z <= startZ - 15) {
                    x = startX;
                    z = startZ;
                    y++;
                } else {
                    x = startX;
                    z--;
                }
            }

            if (y >= 320) {
                if (readInfinitely) {
                    xIndex += direction;
                    startX = xIndex * 16;
                    x = startX;
                    z = startZ;
                    y = -64;
                    logger.info("Moving to next chunk: X=" + xIndex);
                } else {
                    logger.warning("Hit build height limit at Y=320. Blocks read: " + blocksRead);
                    break;
                }
            }
        }

        if (blocksRead >= MAX_BLOCKS_PER_CHUNK) {
            logger.severe("Possible data corruption detected! Read " + blocksRead + " blocks without finding end marker.");
        }

        // Convert hex to ASCII
        StringBuilder asciiBuilder = new StringBuilder();
        for (int i = 0; i < hexBuilder.length(); i += 2) {
            if (i + 1 < hexBuilder.length()) {
                String hexByte = new String(new char[]{hexBuilder.charAt(i), hexBuilder.charAt(i + 1)});
                try {
                    asciiBuilder.append(hexToAscii(hexByte));
                } catch (Exception e) {
                    logger.warning("Invalid hex sequence: " + hexByte);
                    break;
                }
            }
        }

        return asciiBuilder.toString();
    }

    public void deleteChunkCompletely(int xIndex, int zIndex, boolean readInfinitely, int direction) {
        int startX = xIndex * 16;
        int startZ = -1 + (zIndex * 16);

        // Clean every block in the chunk area
        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z > startZ - 16; z--) {
                for (int y = -64; y < 320; y++) {
                    Block current = world.getBlockAt(x, y, z);

                    if (isWoolBlock(current.getType())) {
                        if (y == -64) {
                            current.setType(Material.GRASS_BLOCK);
                        } else {
                            current.setType(Material.AIR);
                        }
                    }
                }
            }
        }

        logger.info("Completely cleaned chunk at X:" + xIndex + ", Z:" + zIndex);
    }
    
    public void deleteChunk(int xIndex, int zIndex, boolean readInfinitely, int direction) {

        // Calculate starting scan coordinates based on given index

        int startX = xIndex * 16;
        int startZ = -1 + (zIndex * 16);

        int x = startX;
        int z = startZ;
        int y = -64;

        boolean stillData = true;

        while (stillData) {
            Block current = world.getBlockAt(x, y, z);

            if (isWoolBlock(current.getType())) {
                if (y == -64) current.setType(Material.GRASS_BLOCK);
                else
                    current.setType(Material.AIR);
            } else {
                stillData = false;
                break;
            }

            x++;

            if (x >= (startX + 16)) {
                if (z <= startZ - 15) {
                    x = startX;
                    z = startZ;
                    y++;
                } else {
                    x = startX;
                    z--;
                }
            }

            if (y >= 320) {
                if (readInfinitely) {
                    xIndex += direction;
                    startX = xIndex * 16;
                    x = startX;
                    z = startZ;
                    y = -64;
                } else {
                    logger.info("Ran out of build height, discontinuing delete!");
                }
            }

        }

    }

    public int getTableIndex(String tableTitle, int indexOffset) {

        if (!tableTitle.isEmpty()) {

            String tableStartIndex = readChunk(0, 1, false, 1);

            if (tableStartIndex.isEmpty() || tableStartIndex.equals("0")) {
                return 0;
            } else {

                int currentIndex = Integer.parseInt(tableStartIndex);
                String currentMetadata = readChunk(0, currentIndex + indexOffset, false, 1);

                String title = DataUtilities.parseTitle(currentMetadata);
                int nextIndex = DataUtilities.parseNextIndexTable(currentMetadata);

                logger.info("found table title: " + title);

                if (title.equals(tableTitle)) return currentIndex;
                else {
                    if (nextIndex == 0) {
                        return 0;
                    } else {
                        currentIndex = nextIndex;
                        currentMetadata = readChunk(0, currentIndex + indexOffset, false, 1);
                        title = DataUtilities.parseTitle(currentMetadata);
                        nextIndex = DataUtilities.parseNextIndexTable(currentMetadata);
                    }
                }

                while (nextIndex != 0) {
                    logger.info("found table title: " + title);
                    if (title.equals(tableTitle)) return currentIndex;
                    else {
                        currentIndex = nextIndex;
                        currentMetadata = readChunk(0, currentIndex + indexOffset, false, 1);
                        title = DataUtilities.parseTitle(currentMetadata);
                        nextIndex = DataUtilities.parseNextIndexTable(currentMetadata);
                    }

                }
                logger.info("found table title: " + title);
                if (title.equals(tableTitle)) return currentIndex;

                // No table found, return 0
                return 0;

            }

        } else return 0;

    }

}
