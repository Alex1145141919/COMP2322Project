// MultiThreadWebServer.java
// 整合阶段5~9：多线程 + GET/HEAD + 五种状态码 + Last-Modified/304 + keep-alive + 日志
import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiThreadWebServer {
    private static final int PORT = 8080;
    private static final String LOG_FILE = "log.txt";
    private static final SimpleDateFormat HTTP_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
    static {
        HTTP_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("多线程 Web 服务器已启动，端口：" + PORT);
        System.out.println("访问 http://127.0.0.1:" + PORT + "/");

        while (true) {
            Socket clientSocket = serverSocket.accept();
            System.out.println("[主线程] 新连接来自：" + clientSocket.getInetAddress());
            new Thread(new ClientHandler(clientSocket)).start();
        }
    }

    // 写日志（线程安全，追加模式）
    public static synchronized void writeLog(String clientIp, String requestFile, int statusCode) {
        try (FileWriter fw = new FileWriter(LOG_FILE, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            out.printf("%s - - [%s] \"GET %s HTTP/1.1\" %d%n", clientIp, timestamp, requestFile, statusCode);
        } catch (IOException e) {
            System.err.println("写日志失败: " + e.getMessage());
        }
    }

    // 获取文件的最后修改时间（HTTP 格式）
    public static String getLastModified(File file) {
        long lastModified = file.lastModified();
        return HTTP_DATE_FORMAT.format(new Date(lastModified));
    }
}

// 每个客户端请求的处理类（支持 keep-alive）
class ClientHandler implements Runnable {
    private Socket socket;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        String threadName = Thread.currentThread().getName();
        try {
            // 设置 Socket 读取超时（防止持久连接一直阻塞）
            socket.setSoTimeout(5000); // 5秒无新请求则自动关闭
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();

            boolean keepAlive = true;
            while (keepAlive) {
                // 读取请求行
                String requestLine = in.readLine();
                if (requestLine == null || requestLine.isEmpty()) {
                    break;
                }
                System.out.println("[" + threadName + "] 请求行: " + requestLine);

                // 读取所有请求头，并提取重要字段
                String line;
                String host = null;
                String connectionHeader = "close";
                String ifModifiedSince = null;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    line = line.trim();
                    if (line.toLowerCase().startsWith("host:")) {
                        host = line.substring(5).trim();
                    } else if (line.toLowerCase().startsWith("connection:")) {
                        connectionHeader = line.substring(11).trim().toLowerCase();
                    } else if (line.toLowerCase().startsWith("if-modified-since:")) {
                        ifModifiedSince = line.substring(19).trim();
                    }
                }

                // 解析请求行
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) {
                    sendErrorResponse(out, 400, "Bad Request");
                    MultiThreadWebServer.writeLog(socket.getInetAddress().getHostAddress(), "???", 400);
                    break;
                }
                String method = parts[0].toUpperCase();
                String path = parts[1];

                // 只支持 GET 和 HEAD
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    sendErrorResponse(out, 405, "Method Not Allowed");
                    MultiThreadWebServer.writeLog(socket.getInetAddress().getHostAddress(), path, 405);
                    break;
                }

                // 默认首页
                if (path.equals("/")) {
                    path = "/index.html";
                }
                // 防止路径遍历攻击（简单过滤）
                if (path.contains("..")) {
                    sendErrorResponse(out, 403, "Forbidden");
                    MultiThreadWebServer.writeLog(socket.getInetAddress().getHostAddress(), path, 403);
                    break;
                }
                String filename = path.substring(1); // 去掉开头的 /
                File file = new File(filename);

                // 检查文件是否存在且可读
                if (!file.exists()) {
                    sendErrorResponse(out, 404, "Not Found");
                    MultiThreadWebServer.writeLog(socket.getInetAddress().getHostAddress(), path, 404);
                    break;
                }
                if (!file.canRead()) {
                    sendErrorResponse(out, 403, "Forbidden");
                    MultiThreadWebServer.writeLog(socket.getInetAddress().getHostAddress(), path, 403);
                    break;
                }

                // 处理 Last-Modified 和 If-Modified-Since（304）
                String lastModified = MultiThreadWebServer.getLastModified(file);
                if (ifModifiedSince != null) {
                    try {
                        // 解析客户端发送的时间
                        SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                        Date ifModifiedSinceDate = fmt.parse(ifModifiedSince);
                        long fileLastModified = file.lastModified();
                        // 如果文件修改时间 <= 客户端缓存时间，返回 304
                        if (fileLastModified <= ifModifiedSinceDate.getTime()) {
                            send304Response(out, lastModified);
                            MultiThreadWebServer.writeLog(socket.getInetAddress().getHostAddress(), path, 304);
                            // 决定是否继续 keep-alive
                            keepAlive = connectionHeader.equals("keep-alive");
                            if (!keepAlive) break;
                            continue;
                        }
                    } catch (Exception e) {
                        // 解析失败，忽略，继续正常返回文件
                    }
                }

                // 确定 Content-Type
                String contentType = getContentType(filename);

                // 读取文件内容（仅在 GET 时需要）
                byte[] fileContent = null;
                if (method.equals("GET")) {
                    try (FileInputStream fis = new FileInputStream(file)) {
                        fileContent = fis.readAllBytes();
                    } catch (IOException e) {
                        sendErrorResponse(out, 500, "Internal Server Error");
                        MultiThreadWebServer.writeLog(socket.getInetAddress().getHostAddress(), path, 500);
                        break;
                    }
                }

                // 发送响应（200 OK）
                sendOkResponse(out, contentType, fileContent, lastModified, connectionHeader.equals("keep-alive"));
                MultiThreadWebServer.writeLog(socket.getInetAddress().getHostAddress(), path, 200);
                System.out.println("[" + threadName + "] 已响应: " + path + " (200)");

                // 决定是否保持连接
                keepAlive = connectionHeader.equals("keep-alive");
                if (!keepAlive) {
                    break;
                }
            } // end while keepAlive

            socket.close();
            System.out.println("[" + threadName + "] 连接关闭");
        } catch (SocketTimeoutException e) {
            System.out.println("[" + threadName + "] 读取超时，关闭连接");
            try { socket.close(); } catch (IOException ex) {}
        } catch (IOException e) {
            System.out.println("[" + threadName + "] 处理异常: " + e.getMessage());
            try { socket.close(); } catch (IOException ex) {}
        }
    }

    private String getContentType(String filename) {
        if (filename.endsWith(".html") || filename.endsWith(".htm")) return "text/html; charset=utf-8";
        if (filename.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (filename.endsWith(".png")) return "image/png";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".gif")) return "image/gif";
        if (filename.endsWith(".css")) return "text/css";
        if (filename.endsWith(".js")) return "application/javascript";
        return "application/octet-stream";
    }

    // 发送 200 OK 响应（支持 keep-alive）
    private void sendOkResponse(OutputStream out, String contentType, byte[] content, String lastModified, boolean keepAlive) throws IOException {
        int contentLength = (content == null) ? 0 : content.length;
        String statusLine = "HTTP/1.1 200 OK\r\n";
        String headers = statusLine +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + contentLength + "\r\n" +
                "Last-Modified: " + lastModified + "\r\n" +
                (keepAlive ? "Connection: keep-alive\r\n" : "Connection: close\r\n") +
                "\r\n";
        out.write(headers.getBytes("UTF-8"));
        if (content != null && contentLength > 0) {
            out.write(content);
        }
        out.flush();
    }

    // 发送 304 Not Modified 响应（无 body）
    private void send304Response(OutputStream out, String lastModified) throws IOException {
        String response = "HTTP/1.1 304 Not Modified\r\n" +
                "Last-Modified: " + lastModified + "\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n";
        out.write(response.getBytes("UTF-8"));
        out.flush();
    }

    // 发送错误响应（400, 403, 404, 405, 500）
    private void sendErrorResponse(OutputStream out, int statusCode, String statusText) throws IOException {
        String htmlBody = String.format("<html><body><h1>%d %s</h1></body></html>", statusCode, statusText);
        byte[] bodyBytes = htmlBody.getBytes("UTF-8");
        String statusLine;
        switch (statusCode) {
            case 400: statusLine = "HTTP/1.1 400 Bad Request\r\n"; break;
            case 403: statusLine = "HTTP/1.1 403 Forbidden\r\n"; break;
            case 404: statusLine = "HTTP/1.1 404 Not Found\r\n"; break;
            case 405: statusLine = "HTTP/1.1 405 Method Not Allowed\r\n"; break;
            case 500: statusLine = "HTTP/1.1 500 Internal Server Error\r\n"; break;
            default:  statusLine = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n";
        }
        String headers = statusLine +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        out.write(headers.getBytes("UTF-8"));
        out.write(bodyBytes);
        out.flush();
    }
}