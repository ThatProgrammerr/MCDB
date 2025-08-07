import java.io.IOException;
import java.io.InputStream;
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
import java.util.Properties;
import java.util.Scanner;

public class Main {
    private String baseUrl;
    private HttpClient client;
    private static final String CONFIG_FILE = "config.properties";

    public Main() {
        this.client = HttpClient.newHttpClient();
        this.baseUrl = loadBaseUrlFromConfig();

        System.out.println("Using base URL: " + this.baseUrl);
        System.out.println();

        System.out.println("MCDB Query/Upload Client. Available commands:");
        System.out.println("  query <your query>         - Send a query to /query endpoint");
        System.out.println("  upload <file_path>         - Upload a file to /upload endpoint");
        System.out.println("  upload_dir <dir_path>      - Upload all files in a directory (with delay)");
        System.out.println("  config                     - Show current configuration");
        System.out.println("  quit                       - Exit the application");
        System.out.println();

        Scanner s = new Scanner(System.in);
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

    private String loadBaseUrlFromConfig() {
        Properties props = new Properties();
        Path configPath = Paths.get(CONFIG_FILE);

        // Try to load from file first
        if (Files.exists(configPath)) {
            try (InputStream input = Files.newInputStream(configPath)) {
                props.load(input);
                String url = props.getProperty("base.url");
                if (url != null && !url.trim().isEmpty()) {
                    url = url.trim();
                    // Remove trailing slash if present
                    if (url.endsWith("/")) {
                        url = url.substring(0, url.length() - 1);
                    }
                    System.out.println("Loaded base URL from " + CONFIG_FILE);
                    return url;
                }
            } catch (IOException e) {
                System.err.println("Warning: Could not read config file " + CONFIG_FILE + ": " + e.getMessage());
            }
        }

        // Config file doesn't exist - prompt user
        return promptForConfig();
    }

    private String promptForConfig() {
        Scanner s = new Scanner(System.in);

        System.out.println("No config file found.");
        System.out.println("To configure a custom base URL:");
        System.out.println("  1. Copy 'config.properties.example' to 'config.properties'");
        System.out.println("  2. Edit 'config.properties' with your desired base URL");
        System.out.println("  3. Restart the application");
        System.out.println();
        System.out.print("Press Enter to continue with default (http://localhost:8000) or Ctrl+C to exit: ");

        s.nextLine(); // Wait for user to press Enter

        String defaultUrl = "http://localhost:8000";
        System.out.println("Using default base URL: " + defaultUrl);
        return defaultUrl;
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

            case "config":
                showConfig();
                break;

            default:
                System.out.println("Unknown command: " + command);
                System.out.println("Available commands: query, upload, upload_dir, config, quit");
        }
    }

    private void showConfig() {
        System.out.println("Current configuration:");
        System.out.println("  Base URL: " + baseUrl);
        System.out.println("  Config file: " + CONFIG_FILE);

        Path configPath = Paths.get(CONFIG_FILE);
        if (Files.exists(configPath)) {
            System.out.println("  Status: Config file exists");
            System.out.println();
            System.out.println("To change the base URL, edit " + CONFIG_FILE + " and restart.");
        } else {
            System.out.println("  Status: Using default (no config file)");
            System.out.println();
            System.out.println("To set a custom base URL:");
            System.out.println("  1. Copy 'config.properties.example' to 'config.properties'");
            System.out.println("  2. Edit 'config.properties' with your desired base URL");
            System.out.println("  3. Restart the application");
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