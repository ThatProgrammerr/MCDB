import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Scanner;

public class Main {
    private String baseUrl;
    private HttpClient client;

    public Main() {
        this.client = HttpClient.newHttpClient();

        Scanner s = new Scanner(System.in);

        // Get base URL from user
        System.out.print("Enter base URL (e.g., http://localhost:8000): ");
        this.baseUrl = s.nextLine().trim();

        // Remove trailing slash if present
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        System.out.println("MCDB Query/Upload Client. Available commands:");
        System.out.println("  query <your query>         - Send a query to /query endpoint");
        System.out.println("  upload <file_path>         - Upload a file to /upload endpoint");
        System.out.println("  upload_dir <dir_path>      - Upload all files in a directory (with delay)");
        System.out.println("  quit                       - Exit the application");
        System.out.println();

        while (s.hasNextLine()) {
            String input = s.nextLine().trim();

            if (input.equals("quit")) {
                break;
            }

            processCommand(input);
        }

        s.close();
        System.out.println("Goodbye!");
    }

    public void processCommand(String input) {
        if (input.isEmpty()) {
            return;
        }

        String[] parts = input.split(" ", 2);
        String command = parts[0].toLowerCase();

        switch (command) {
            case "query":
                if (parts.length < 2) {
                    System.out.println("Usage: query <your query>");
                    return;
                }
                sendQuery(parts[1]);
                break;

            case "upload":
                if (parts.length < 2) {
                    System.out.println("Usage: upload <file_path>");
                    return;
                }
                sendUpload(parts[1]);
                break;

            case "upload_dir":
                if (parts.length < 2) {
                    System.out.println("Usage: upload_dir <directory_path>");
                    return;
                }
                sendDirectoryUpload(parts[1]);
                break;

            default:
                System.out.println("Unknown command: " + command);
                System.out.println("Available commands: query, upload, upload_dir, quit");
        }
    }

    public void sendQuery(String query) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();

        HttpRequest request = builder
                .uri(URI.create(baseUrl + "/query"))
                .POST(HttpRequest.BodyPublishers.ofString(query))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Query Response code: " + response.statusCode());
            System.out.println("Query Response body: " + response.body());
        } catch (IOException | InterruptedException e) {
            System.err.println("Error sending query: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void sendUpload(String filePath) {
        try {
            Path path = Paths.get(filePath);

            if (!Files.exists(path)) {
                System.err.println("File not found: " + filePath);
                return;
            }

            if (!Files.isRegularFile(path)) {
                System.err.println("Path is not a regular file: " + filePath);
                return;
            }

            // Read file bytes
            byte[] fileBytes = Files.readAllBytes(path);

            // Encode to base64
            String base64Content = Base64.getEncoder().encodeToString(fileBytes);

            // Get filename
            String fileName = path.getFileName().toString();

            // Detect MIME type
            String mimeType = Files.probeContentType(path);
            if (mimeType == null) {
                // Fallback MIME type detection based on file extension
                mimeType = getMimeTypeFromExtension(fileName);
            }

            // Format the upload data: fileName.ext;mime;base64_encodedfile
            String uploadData = fileName + ";" + mimeType + ";" + base64Content;

            System.out.println("Uploading file: " + fileName);
            System.out.println("MIME type: " + mimeType);
            System.out.println("File size: " + fileBytes.length + " bytes");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/upload"))
                    .POST(HttpRequest.BodyPublishers.ofString(uploadData))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Upload Response code: " + response.statusCode());
            System.out.println("Upload Response body: " + response.body());

        } catch (IOException | InterruptedException e) {
            System.err.println("Error uploading file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void sendDirectoryUpload(String directoryPath) {
        try {
            Path dirPath = Paths.get(directoryPath);

            if (!Files.exists(dirPath)) {
                System.err.println("Directory not found: " + directoryPath);
                return;
            }

            if (!Files.isDirectory(dirPath)) {
                System.err.println("Path is not a directory: " + directoryPath);
                return;
            }

            // Get delay configuration
            Scanner s = new Scanner(System.in);
            System.out.print("Enter delay between uploads in milliseconds (default 1000): ");
            String delayInput = s.nextLine().trim();

            long delayMs = 1000; // default 1 second
            if (!delayInput.isEmpty()) {
                try {
                    delayMs = Long.parseLong(delayInput);
                } catch (NumberFormatException e) {
                    System.out.println("Invalid delay value, using default 1000ms");
                }
            }

            // Collect all files in the directory
            List<Path> filesToUpload = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
                for (Path entry : stream) {
                    if (Files.isRegularFile(entry)) {
                        filesToUpload.add(entry);
                    }
                }
            }

            if (filesToUpload.isEmpty()) {
                System.out.println("No files found in directory: " + directoryPath);
                return;
            }

            System.out.println("Found " + filesToUpload.size() + " files to upload");
            System.out.println("Upload delay: " + delayMs + "ms");
            System.out.print("Continue? (y/N): ");

            String confirm = s.nextLine().trim().toLowerCase();
            if (!confirm.equals("y") && !confirm.equals("yes")) {
                System.out.println("Directory upload cancelled");
                return;
            }

            // Upload each file with delay
            int successCount = 0;
            int failCount = 0;

            for (int i = 0; i < filesToUpload.size(); i++) {
                Path filePath = filesToUpload.get(i);
                System.out.println("\n[" + (i + 1) + "/" + filesToUpload.size() + "] Uploading: " + filePath.getFileName());

                boolean success = uploadSingleFile(filePath);
                if (success) {
                    successCount++;
                    System.out.println("✓ Upload successful");
                } else {
                    failCount++;
                    System.out.println("✗ Upload failed");
                }

                // Add delay between uploads (except for the last file)
                if (i < filesToUpload.size() - 1 && delayMs > 0) {
                    System.out.println("Waiting " + delayMs + "ms before next upload...");
                    Thread.sleep(delayMs);
                }
            }

            System.out.println("\nDirectory upload completed!");
            System.out.println("Successful uploads: " + successCount);
            System.out.println("Failed uploads: " + failCount);
            System.out.println("Total files: " + filesToUpload.size());

        } catch (IOException | InterruptedException e) {
            System.err.println("Error during directory upload: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean uploadSingleFile(Path filePath) {
        try {
            // Read file bytes
            byte[] fileBytes = Files.readAllBytes(filePath);

            // Encode to base64
            String base64Content = Base64.getEncoder().encodeToString(fileBytes);

            // Get filename
            String fileName = filePath.getFileName().toString();

            // Detect MIME type
            String mimeType = Files.probeContentType(filePath);
            if (mimeType == null) {
                mimeType = getMimeTypeFromExtension(fileName);
            }

            // Format the upload data
            String uploadData = fileName + ";" + mimeType + ";" + base64Content;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/upload"))
                    .POST(HttpRequest.BodyPublishers.ofString(uploadData))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("Response code: " + response.statusCode());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("Response body: " + response.body());
                return true;
            } else {
                System.err.println("Error response body: " + response.body());
                return false;
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Error uploading file " + filePath.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    private String getMimeTypeFromExtension(String fileName) {
        String extension = "";
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            extension = fileName.substring(lastDot + 1).toLowerCase();
        }

        // Common MIME types
        switch (extension) {
            case "png": return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "webp": return "image/webp";
            case "pdf": return "application/pdf";
            case "txt": return "text/plain";
            case "html": return "text/html";
            case "css": return "text/css";
            case "js": return "application/javascript";
            case "json": return "application/json";
            case "xml": return "application/xml";
            case "zip": return "application/zip";
            case "mp4": return "video/mp4";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default: return "application/octet-stream";
        }
    }

    public static void main(String[] args) {
        new Main();
    }
}