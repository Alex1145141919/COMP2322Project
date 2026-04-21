Author: Yemuhan
Student ID: 24103714D

1. Requirements
   - Java JDK 11 or higher
   - OS: Windows / macOS / Linux

2. How to Compile
   Open a terminal in the project folder and run:
   javac MultiThreadWebServer.java

3. How to Run
   java MultiThreadWebServer
   The server will listen on port 8080.

4. How to Test
   - Open a browser and visit http://127.0.0.1:8080/
   - Supported file types: .html, .txt, .jpg, .png, etc.
   - Test HEAD and 304 with curl:
     curl -I http://127.0.0.1:8080/index.html
     curl -H "If-Modified-Since: Fri, 31 Dec 2030 00:00:00 GMT" http://127.0.0.1:8080/index.html

5. Features
   - Multi-threaded concurrent processing
   - GET and HEAD methods
   - Status codes: 200, 400, 403, 404, 304
   - Last-Modified and If-Modified-Since (304 cache validation)
   - Connection: keep-alive persistent connections
   - Access logging (log.txt)

6. Notes
   - Default port is 8080. To change it, modify the PORT constant in the code.
   - The log file appends each request; you may delete it manually if needed.