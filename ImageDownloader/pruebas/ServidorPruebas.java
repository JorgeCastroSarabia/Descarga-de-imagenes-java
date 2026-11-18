import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;


public class ServidorPruebas {

    private static final String GALERIA = """
            <!DOCTYPE html>
            <html lang="es">
            <head><meta charset="UTF-8"><title>Galería de pruebas</title></head>
            <body>
              <h1>Galería de pruebas</h1>
              <!-- Esta imagen está en un comentario y debe ignorarse:
                   <img src="img/comentada.png"> -->
              <img src="http://localhost:{PUERTO}/img/logo.png" alt="P05 absoluta">
              <img src="img/foto.jpg" alt="P06 relativa">
              <IMG SRC='/img/logo.png' alt="P07 duplicada (misma URL que la primera)">
              <img src=img/sin-comillas.gif alt="atributo sin comillas">
              <img src="redir/otra.png" alt="redirección 302">
              <img src="img/no-existe.png" alt="P08 inexistente (404)">
              <img src="img/falso.png" alt="P09 no es una imagen">
              <img src="img/enorme.png" alt="P11 más de 10 MB, sin Content-Length">
              <img src="img/enorme-cl.png" alt="P11 más de 10 MB, con Content-Length">
              <img data-src="img/lazy.png" src="img/otra.png" alt="data-src no debe confundirse con src">
              <img src="data:image/png;base64,iVBORw0KGgo=" alt="esquema data: no admitido">
              <img src="" alt="referencia vacía">
              <img alt="sin atributo src">
            </body>
            </html>
            """;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int puerto = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        HttpServer servidor = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), puerto), 0);
        servidor.setExecutor(Executors.newCachedThreadPool());
        servidor.createContext("/", ServidorPruebas::atender);
        servidor.start();

        System.out.println("Servidor de pruebas en http://localhost:" + puerto + "/galeria.html");
        System.out.println("Ctrl+C para detenerlo.");
    }

    private static void atender(HttpExchange ex) throws IOException {
        String ruta = ex.getRequestURI().getPath();
        System.out.println(ex.getRequestMethod() + " " + ruta);
        int puerto = ex.getLocalAddress().getPort();

        switch (ruta) {
            case "/galeria.html" -> responder(ex, 200, "text/html; charset=UTF-8",
                    GALERIA.replace("{PUERTO}", String.valueOf(puerto)).getBytes(StandardCharsets.UTF_8));
            case "/vacia.html" -> responder(ex, 200, "text/html; charset=UTF-8",
                    "<html><body><h1>Página sin imágenes</h1></body></html>".getBytes(StandardCharsets.UTF_8));
            case "/sub/pagina.html" -> responder(ex, 200, "text/html; charset=UTF-8",
                    ("<html><body><img src=\"../img/logo.png\"><img src=\"../img/foto.jpg\"></body></html>")
                            .getBytes(StandardCharsets.UTF_8));
            case "/lento.html" -> {
                try {
                    Thread.sleep(20_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                responder(ex, 200, "text/html", "<html></html>".getBytes(StandardCharsets.UTF_8));
            }
            case "/img/logo.png" -> responder(ex, 200, "image/png", imagen("png", new Color(200, 40, 40)));
            case "/img/otra.png" -> responder(ex, 200, "image/png", imagen("png", new Color(40, 90, 200)));
            case "/img/foto.jpg" -> responder(ex, 200, "image/jpeg", imagen("jpg", new Color(40, 160, 70)));
            case "/img/sin-comillas.gif" -> responder(ex, 200, "image/gif", imagen("gif", new Color(230, 170, 20)));
            case "/img/falso.png" -> responder(ex, 200, "text/html",
                    "<html>Esto parece un .png pero es HTML</html>".getBytes(StandardCharsets.UTF_8));
            case "/img/enorme.png" -> enorme(ex, false);
            case "/img/enorme-cl.png" -> enorme(ex, true);
            case "/redir/otra.png" -> {
                ex.getResponseHeaders().set("Location", "/img/otra.png");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            }
            default -> responder(ex, 404, "text/plain; charset=UTF-8",
                    "404 - no existe".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void responder(HttpExchange ex, int codigo, String tipo, byte[] cuerpo) throws IOException {
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.sendResponseHeaders(codigo, cuerpo.length);
        try (OutputStream salida = ex.getResponseBody()) {
            salida.write(cuerpo);
        }
    }


    private static void enorme(HttpExchange ex, boolean conContentLength) throws IOException {
        long total = 11L * 1024 * 1024;
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.sendResponseHeaders(200, conContentLength ? total : 0); // 0 = transferencia por bloques
        try (OutputStream salida = ex.getResponseBody()) {
            byte[] bloque = new byte[64 * 1024];
            long enviados = 0;
            while (enviados < total) {
                int n = (int) Math.min(bloque.length, total - enviados);
                salida.write(bloque, 0, n);
                enviados += n;
            }
        } catch (IOException e) {

        }
    }

    private static byte[] imagen(String formato, Color color) throws IOException {
        BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 120, 80);
        g.setColor(Color.WHITE);
        g.drawString(formato.toUpperCase(), 40, 45);
        g.dispose();
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ImageIO.write(img, formato, salida);
        return salida.toByteArray();
    }
}
