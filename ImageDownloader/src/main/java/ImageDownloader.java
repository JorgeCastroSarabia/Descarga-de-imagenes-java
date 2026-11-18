import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;


public class ImageDownloader {

    // ---------------------------------------------------------------- constantes

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024; // 10 MB por imagen
    private static final long MAX_HTML_BYTES = 5L * 1024 * 1024;   // 5 MB para el HTML
    private static final int MAX_REDIRECCIONES = 5;
    private static final String USER_AGENT = "ImageDownloader/1.0 (practica escolar en Java)";
    private static final String SEPARADOR = "---------------------------------------";


    private static final Pattern COMENTARIO_HTML =
            Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern ETIQUETA_IMG =
            Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern ATRIBUTO_SRC = Pattern.compile(
            "(?<![\\w:-])src\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ESQUEMA =
            Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.\\-]*):");
    private static final Pattern CHARSET_HEADER =
            Pattern.compile("charset\\s*=\\s*\"?([\\w.:\\-]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARSET_META =
            Pattern.compile("<meta[^>]+charset\\s*=\\s*[\"']?([\\w.:\\-]+)", Pattern.CASE_INSENSITIVE);


    public record Pagina(URL url, String html) { }


    public record Resultado(boolean exito, String archivo, String estado, long bytes) { }


    public static class ErrorHttp extends IOException {
        private static final long serialVersionUID = 1L;
        private final int codigo;

        public ErrorHttp(int codigo) {
            super("Error HTTP " + codigo);
            this.codigo = codigo;
        }

        public int getCodigo() {
            return codigo;
        }
    }



    public static void main(String[] args) {
        int codigo = ejecutar(args);
        if (codigo != 0) {
            System.exit(codigo);
        }
    }

    private static int ejecutar(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Uso: java ImageDownloader <URL> [directorio]");
            return 1;
        }

        System.out.println("ImageDownloader");
        System.out.println(SEPARADOR);


        URL urlPagina;
        try {
            urlPagina = validarURL(args[0]);
        } catch (IllegalArgumentException e) {
            System.err.println("URL inválida: " + e.getMessage());
            return 1;
        }

        Path directorio;
        try {
            directorio = Path.of(args.length == 2 ? args[1] : "imagenes");
        } catch (InvalidPathException e) {
            System.err.println("Directorio inválido: " + e.getMessage());
            return 1;
        }
        if (Files.exists(directorio) && !Files.isDirectory(directorio)) {
            System.err.println("El destino existe y no es un directorio: " + directorio);
            return 1;
        }


        System.out.println("Conectando con: " + urlPagina);
        System.out.println();

        Pagina pagina;
        try {
            pagina = obtenerPagina(urlPagina);
        } catch (IOException e) {
            if (e instanceof ErrorHttp errorHttp) {
                System.out.println("Código HTTP: " + errorHttp.getCodigo());
            }
            System.err.println("No se pudo obtener la página: " + describirError(e));
            return 1;
        }


        System.out.println();
        System.out.println("Analizando documento HTML...");
        System.out.println();

        Set<String> referencias = extraerImagenes(pagina.html());
        Map<String, URL> imagenes = new LinkedHashMap<>();
        List<String> ignoradas = new ArrayList<>();
        int resueltas = 0;

        for (String referencia : referencias) {
            try {
                URL absoluta = resolverImagen(pagina.url(), referencia);
                resueltas++;
                imagenes.putIfAbsent(absoluta.toExternalForm(), absoluta);
            } catch (IllegalArgumentException | MalformedURLException e) {
                ignoradas.add(abreviar(referencia.isEmpty() ? "(vacía)" : referencia, 50)
                        + " -> " + e.getMessage());
            }
        }

        System.out.println("Imágenes encontradas: " + imagenes.size());
        if (resueltas > imagenes.size()) {
            System.out.println("Referencias duplicadas omitidas: " + (resueltas - imagenes.size()));
        }
        if (!ignoradas.isEmpty()) {
            System.out.println("Referencias ignoradas: " + ignoradas.size());
            for (String motivo : ignoradas) {
                System.out.println("  - " + motivo);
            }
        }

        if (imagenes.isEmpty()) {
            System.out.println();
            System.out.println("No hay imágenes que descargar.");
            return 0;
        }


        try {
            Files.createDirectories(directorio);
        } catch (IOException e) {
            System.err.println("No se pudo crear el directorio " + directorio + ": " + e.getMessage());
            return 1;
        }

        System.out.println();
        int exitosas = 0;
        int fallidas = 0;
        int total = imagenes.size();
        int numero = 0;
        List<String[]> filasCsv = new ArrayList<>();

        for (URL imagen : imagenes.values()) {
            numero++;
            Resultado resultado = descargarDetallado(imagen, directorio, numero);
            if (resultado.exito()) {
                exitosas++;
                imprimirLinea(numero, total, nombreOrigen(imagen),
                        "OK (" + resultado.archivo() + ", " + resultado.bytes() + " bytes)");
            } else {
                fallidas++;
                imprimirLinea(numero, total, nombreOrigen(imagen), "ERROR: " + resultado.estado());
            }
            filasCsv.add(new String[] {
                    imagen.toExternalForm(),
                    resultado.exito() ? resultado.archivo() : "—",
                    resultado.estado(),
                    String.valueOf(resultado.bytes())
            });
        }


        Path csv = directorio.resolve("descargas.csv");
        try {
            escribirCsv(csv, filasCsv);
        } catch (IOException e) {
            System.err.println("No se pudo escribir " + csv + ": " + e.getMessage());
        }

        System.out.println();
        System.out.println(SEPARADOR);
        System.out.println("Descargas exitosas: " + exitosas);
        System.out.println("Descargas fallidas: " + fallidas);
        System.out.println("Directorio: " + directorio + "/");
        System.out.println("Registro: " + csv);
        return 0;
    }


    public static URL validarURL(String argumento) {
        if (argumento == null || argumento.isBlank()) {
            throw new IllegalArgumentException("la dirección está vacía");
        }

        URI uri;
        try {
            uri = URI.create(argumento.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sintaxis incorrecta (" + e.getMessage() + ")", e);
        }

        String esquema = uri.getScheme();
        if (esquema == null) {
            throw new IllegalArgumentException("falta el protocolo (use http:// o https://)");
        }
        if (!esquema.equalsIgnoreCase("http") && !esquema.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException(
                    "protocolo no admitido: " + esquema + " (solo http y https)");
        }

        URL url;
        try {
            url = uri.toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("URL no admitida: " + e.getMessage(), e);
        }
        if (url.getHost() == null || url.getHost().isBlank()) {
            throw new IllegalArgumentException("la dirección no contiene un servidor válido");
        }
        return url;
    }



    private static HttpURLConnection abrirConexion(URL url, String accept) throws IOException {
        URL actual = url;
        for (int i = 0; i <= MAX_REDIRECCIONES; i++) {
            URLConnection conexion = actual.openConnection();
            if (!(conexion instanceof HttpURLConnection http)) {
                throw new IOException("Protocolo no admitido: " + actual.getProtocol());
            }
            http.setRequestMethod("GET");
            http.setConnectTimeout(CONNECT_TIMEOUT_MS);
            http.setReadTimeout(READ_TIMEOUT_MS);
            http.setInstanceFollowRedirects(false);
            http.setRequestProperty("User-Agent", USER_AGENT);
            http.setRequestProperty("Accept", accept);

            int codigo;
            try {
                codigo = http.getResponseCode();
            } catch (IOException e) {
                http.disconnect();
                throw e;
            }

            if (codigo >= 300 && codigo < 400 && codigo != 304) {
                String destino = http.getHeaderField("Location");
                http.disconnect();
                if (destino == null || destino.isBlank()) {
                    throw new IOException("Redirección (HTTP " + codigo + ") sin encabezado Location");
                }
                URL siguiente = resolverURL(actual, destino.trim());
                String protocolo = siguiente.getProtocol();
                if (!protocolo.equals("http") && !protocolo.equals("https")) {
                    throw new IOException("Redirección a un protocolo no admitido: " + protocolo);
                }
                if (actual.getProtocol().equals("https") && protocolo.equals("http")) {
                    throw new IOException("Redirección rechazada: degrada de HTTPS a HTTP");
                }
                actual = siguiente;
                continue;
            }

            if (codigo < 200 || codigo > 299) {
                http.disconnect();
                throw new ErrorHttp(codigo);
            }
            return http;
        }
        throw new IOException("Demasiadas redirecciones (máximo " + MAX_REDIRECCIONES + ")");
    }


    public static String obtenerHTML(URL url) throws IOException {
        return obtenerPagina(url).html();
    }


    public static Pagina obtenerPagina(URL url) throws IOException {
        HttpURLConnection conexion = abrirConexion(url, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5");
        try {
            String tipo = conexion.getContentType();
            System.out.println("Código HTTP: " + conexion.getResponseCode());
            System.out.println("Tipo de contenido: " + (tipo == null ? "(no indicado)" : tipo));

            String mime = tipoMime(tipo);
            if (mime != null && !mime.equals("text/html") && !mime.equals("application/xhtml+xml")) {
                throw new IOException("el recurso no es un documento HTML (" + mime + ")");
            }

            byte[] bytes;
            try (InputStream entrada = conexion.getInputStream()) {
                bytes = leerLimitado(entrada, MAX_HTML_BYTES);
            }
            Charset charset = detectarCharset(tipo, bytes);
            return new Pagina(conexion.getURL(), new String(bytes, charset));
        } finally {
            conexion.disconnect();
        }
    }


    private static byte[] leerLimitado(InputStream entrada, long maximo) throws IOException {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int leidos;
        while ((leidos = entrada.read(buffer)) != -1) {
            total += leidos;
            if (total > maximo) {
                throw new IOException("el documento supera el límite de " + (maximo / (1024 * 1024)) + " MB");
            }
            salida.write(buffer, 0, leidos);
        }
        return salida.toByteArray();
    }


    private static Charset detectarCharset(String contentType, byte[] bytes) {
        if (contentType != null) {
            Matcher m = CHARSET_HEADER.matcher(contentType);
            if (m.find()) {
                Charset c = charsetONulo(m.group(1));
                if (c != null) {
                    return c;
                }
            }
        }
        String inicio = new String(bytes, 0, Math.min(bytes.length, 2048), StandardCharsets.ISO_8859_1);
        Matcher meta = CHARSET_META.matcher(inicio);
        if (meta.find()) {
            Charset c = charsetONulo(meta.group(1));
            if (c != null) {
                return c;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset charsetONulo(String nombre) {
        try {
            return Charset.forName(nombre);
        } catch (IllegalArgumentException e) { // nombre ilegal o no soportado
            return null;
        }
    }

    public static Set<String> extraerImagenes(String html) {
        Set<String> referencias = new LinkedHashSet<>();
        String sinComentarios = COMENTARIO_HTML.matcher(html).replaceAll("");

        Matcher etiquetas = ETIQUETA_IMG.matcher(sinComentarios);
        while (etiquetas.find()) {
            Matcher src = ATRIBUTO_SRC.matcher(etiquetas.group());
            if (src.find()) {
                String valor = src.group(1) != null ? src.group(1)
                        : src.group(2) != null ? src.group(2)
                        : src.group(3);
                referencias.add(valor.replace("&amp;", "&").trim());
            }
        }
        return referencias;
    }


    public static URL resolverImagen(URL base, String referencia) throws MalformedURLException {
        if (referencia == null || referencia.isBlank()) {
            throw new IllegalArgumentException("referencia vacía");
        }
        String ref = referencia.trim();

        Matcher esquema = ESQUEMA.matcher(ref);
        if (esquema.find()) {
            String nombre = esquema.group(1).toLowerCase(Locale.ROOT);
            if (!nombre.equals("http") && !nombre.equals("https")) {
                throw new IllegalArgumentException("esquema no admitido: " + nombre + ":");
            }
        }

        URL resuelta = resolverURL(base, ref.replace(" ", "%20"));
        String protocolo = resuelta.getProtocol();
        if (!protocolo.equals("http") && !protocolo.equals("https")) {
            throw new IllegalArgumentException("esquema no admitido: " + protocolo + ":");
        }
        if (resuelta.getHost() == null || resuelta.getHost().isBlank()) {
            throw new IllegalArgumentException("la referencia no tiene un servidor válido");
        }
        return resuelta;
    }

    /** Resuelve una referencia contra una base usando el constructor URL(URL, String). */
    @SuppressWarnings("deprecation") // la práctica indica usar este constructor
    private static URL resolverURL(URL base, String referencia) throws MalformedURLException {
        return new URL(base, referencia);
    }

    // ----------------------------------- Fase 6: descargar y almacenar imágenes

    /**
     * Descarga una imagen y la guarda en {@code directorio} como {@code imagen_NNN.ext}.
     *
     * @return {@code true} si la imagen se descargó correctamente
     */
    public static boolean descargarImagen(URL url, Path directorio, int numero) {
        return descargarDetallado(url, directorio, numero).exito();
    }

    /**
     * Igual que {@link #descargarImagen(URL, Path, int)}, pero devuelve el detalle del resultado
     * (archivo, estado y bytes), que se usa para el reporte en consola y el archivo CSV.
     *
     * <p>Verifica el código HTTP, que el contenido sea {@code image/*}, el límite de 10 MB
     * (por {@code Content-Length} y por bytes realmente leídos) y nunca sobrescribe archivos.
     * Si ocurre un error, elimina el archivo parcial.</p>
     */
    public static Resultado descargarDetallado(URL urlImagen, Path directorio, int numero) {
        HttpURLConnection conexion = null;
        Path destino = null;
        try {
            conexion = abrirConexion(urlImagen, "image/*,*/*;q=0.8");

            String mime = tipoMime(conexion.getContentType());
            if (mime == null || !mime.startsWith("image/")) {
                return fallo("el recurso no es una imagen (" + (mime == null ? "sin tipo" : mime) + ")");
            }
            String extension = extensionPara(mime);
            if (extension == null) {
                return fallo("tipo de imagen no reconocido (" + mime + ")");
            }
            long declarado = conexion.getContentLengthLong();
            if (declarado > MAX_IMAGE_BYTES) {
                return fallo("supera el límite de 10 MB (Content-Length: " + declarado + ")");
            }

            // Reservar un nombre libre de forma atómica: CREATE_NEW nunca sobrescribe
            OutputStream salida = null;
            for (int intento = 1; salida == null; intento++) {
                String nombre = (intento == 1)
                        ? String.format("imagen_%03d.%s", numero, extension)
                        : String.format("imagen_%03d_%d.%s", numero, intento, extension);
                Path candidato = directorio.resolve(nombre);
                try {
                    salida = Files.newOutputStream(candidato,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    destino = candidato;
                } catch (FileAlreadyExistsException e) {
                    // ya existe: probar con el siguiente nombre
                }
            }

            long total = 0;
            try (InputStream entrada = conexion.getInputStream(); OutputStream out = salida) {
                byte[] buffer = new byte[8192];
                int leidos;
                while ((leidos = entrada.read(buffer)) != -1) {
                    total += leidos;
                    if (total > MAX_IMAGE_BYTES) { // Content-Length pudo faltar o mentir
                        throw new IOException("supera el límite de 10 MB");
                    }
                    out.write(buffer, 0, leidos);
                }
            }
            if (total == 0) {
                throw new IOException("la respuesta no contiene datos");
            }
            return new Resultado(true, destino.getFileName().toString(), "Correcto", total);

        } catch (IOException e) {
            eliminarSilenciosamente(destino); // no dejar archivos parciales
            return fallo(describirError(e));
        } finally {
            if (conexion != null) {
                conexion.disconnect();
            }
        }
    }

    private static Resultado fallo(String estado) {
        return new Resultado(false, "—", estado, 0);
    }

    private static void eliminarSilenciosamente(Path archivo) {
        if (archivo != null) {
            try {
                Files.deleteIfExists(archivo);
            } catch (IOException ignorada) {
                // no hay nada más que hacer
            }
        }
    }

    /** Extensión de archivo según el tipo MIME (no según la URL). */
    private static String extensionPara(String mime) {
        return switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            case "image/bmp" -> "bmp";
            case "image/avif" -> "avif";
            case "image/x-icon", "image/vnd.microsoft.icon" -> "ico";
            default -> null;
        };
    }

    // ---------------------------------------- Actividad complementaria: CSV

    private static void escribirCsv(Path archivo, List<String[]> filas) throws IOException {
        StringBuilder sb = new StringBuilder("\uFEFF"); // BOM para que Excel lea bien UTF-8
        sb.append("URL de imagen,Archivo local,Estado,Tamaño (bytes)\r\n");
        for (String[] fila : filas) {
            for (int i = 0; i < fila.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(campoCsv(fila[i]));
            }
            sb.append("\r\n");
        }
        Files.writeString(archivo, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String campoCsv(String valor) {
        if (valor.contains(",") || valor.contains("\"") || valor.contains("\n") || valor.contains("\r")) {
            return "\"" + valor.replace("\"", "\"\"") + "\"";
        }
        return valor;
    }

    private static String tipoMime(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        int punto = contentType.indexOf(';');
        String mime = (punto >= 0 ? contentType.substring(0, punto) : contentType);
        return mime.trim().toLowerCase(Locale.ROOT);
    }

    /** Traduce una excepción de red a un mensaje comprensible. */
    private static String describirError(IOException e) {
        if (e instanceof ErrorHttp) {
            return e.getMessage();
        }
        if (e instanceof SocketTimeoutException) {
            return "tiempo de espera agotado";
        }
        if (e instanceof UnknownHostException) {
            return "no se encontró el servidor: " + e.getMessage();
        }
        if (e instanceof ConnectException) {
            return "no se pudo conectar (servidor no disponible o puerto cerrado)";
        }
        if (e instanceof SSLException) {
            return "error de seguridad SSL/TLS: " + e.getMessage();
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String nombreOrigen(URL url) {
        String ruta = url.getPath();
        String nombre = ruta.substring(ruta.lastIndexOf('/') + 1);
        return nombre.isEmpty() ? url.getHost() : nombre;
    }

    private static String abreviar(String texto, int maximo) {
        String limpio = texto.replaceAll("\\s+", " ");
        return limpio.length() <= maximo ? limpio : limpio.substring(0, maximo - 3) + "...";
    }

    private static void imprimirLinea(int numero, int total, String nombre, String resultado) {
        StringBuilder linea = new StringBuilder("[" + numero + "/" + total + "] " + abreviar(nombre, 28) + " ");
        while (linea.length() < 44) {
            linea.append('.');
        }
        System.out.println(linea + " " + resultado);
    }
}
