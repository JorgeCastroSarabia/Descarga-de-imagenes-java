# ImageDownloader

Aplicación Java de línea de comandos que recibe la URL de una página web, obtiene su
documento HTML, identifica las imágenes referenciadas con etiquetas `<img>` y descarga
cada una en un archivo local.

**Asignatura:** Desarrollo de Sistemas III · **Práctica:** Descarga de imágenes mediante
`URL` y `URLConnection`

## Restricción tecnológica

Toda comunicación con Internet se realiza **exclusivamente** con `URL.openConnection()`,
`URLConnection` y su subclase `HttpURLConnection`. No se utiliza `HttpClient`, Jsoup,
Apache HttpComponents ni ninguna biblioteca externa. `URI` se emplea solo para validar la
dirección y convertirla a `URL`, nunca para conectar.

## Requisitos

- JDK 17 o superior (probado con JDK 21).
- Sin dependencias externas (solo la API estándar de Java).

## Estructura del proyecto

```text
ImageDownloader/
├── .gitignore
├── README.md
├── src/
│   └── main/
│       └── java/
│           └── ImageDownloader.java
└── pruebas/
    └── ServidorPruebas.java      (servidor local para reproducir las pruebas)
```

El directorio `imagenes/` se genera durante la ejecución y está excluido de Git.

## Compilación y ejecución

Desde la raíz del proyecto:

```bash
javac -d out src/main/java/ImageDownloader.java
java -cp out ImageDownloader <URL> [directorio]
```

Ejemplos:

```bash
java -cp out ImageDownloader https://ejemplo.org/galeria.html
java -cp out ImageDownloader https://ejemplo.org/galeria.html ./descargas
```

**En IntelliJ IDEA:** *Run → Edit Configurations → Program arguments* y escribir la URL
(y opcionalmente el directorio).

- Sin segundo argumento, las imágenes se guardan en `./imagenes/`.
- Junto con las imágenes se genera `descargas.csv` (actividad complementaria).

## Funcionamiento

| Método | Responsabilidad |
|---|---|
| `validarURL(String)` | Valida la sintaxis; solo admite `http`/`https` y exige un servidor. |
| `String obtenerHTML(URL)` | Conecta, comprueba el código HTTP y el tipo de contenido, y devuelve el HTML leído con la codificación correcta. |
| `extraerImagenes(String)` | Localiza `<img>` con `Pattern`/`Matcher` y devuelve un `Set` sin repetidos. |
| `resolverImagen(URL, String)` | Resuelve referencias relativas/absolutas con `new URL(base, ref)`; rechaza vacías y esquemas como `data:` o `javascript:`. |
| `boolean descargarImagen(URL, Path, int)` | Descarga una imagen como bytes con las validaciones descritas abajo; devuelve `true` si tuvo éxito. |

Las firmas de los cinco métodos coinciden con las del enunciado. Dos de ellos tienen un
método compañero que hace el trabajo y devuelve más información:

- `obtenerHTML(URL)` delega en `obtenerPagina(URL)`, que además devuelve la URL final
  tras redirecciones (base para resolver referencias relativas).
- `descargarImagen(URL, Path, int)` delega en `descargarDetallado(URL, Path, int)`, que devuelve
  archivo, estado y bytes (para el reporte y el CSV).

### Decisiones de diseño

- **Conexión por recurso:** el HTML y cada imagen usan su propia `URLConnection`.
- **Tiempos máximos:** 10 s de conexión y 15 s de lectura.
- **Codificación del HTML:** se usa el `charset` del encabezado `Content-Type`; si falta, la
  etiqueta `<meta charset>`; si tampoco existe, UTF-8.
- **Redirecciones:** se desactiva el seguimiento automático y se siguen manualmente (máximo 5),
  validando cada destino: solo `http`/`https` y sin degradar de HTTPS a HTTP.
- **URL base:** las referencias relativas se resuelven contra la URL *final* de la página
  (la obtenida tras redirecciones), como haría un navegador.
- **Tipo de archivo:** la extensión se decide por el tipo MIME (`image/png`, `image/jpeg`,
  `image/gif`, `image/webp`; además `svg`, `bmp`, `avif`, `ico`), no por la URL.
- **Nombres:** `imagen_001.png`, `imagen_002.jpg`... según el orden de aparición.
- **Sin sobrescritura:** los archivos se crean con `CREATE_NEW`; si el nombre existe se usa
  `imagen_001_2.png`, etc. (`descargas.csv` sí se regenera en cada ejecución).
- **Límite de 10 MB por imagen:** se comprueba `Content-Length` y también los bytes
  realmente leídos, porque ese encabezado puede faltar o ser incorrecto.
- **Sin archivos parciales:** si una descarga falla, el archivo incompleto se elimina.
- **Duplicados:** referencias que resuelven a la misma URL se descargan una sola vez.
- **Códigos de salida:** `0` si la aplicación completó el proceso (aunque alguna imagen falle);
  `1` ante errores de uso, URL inválida o imposibilidad de obtener la página.

### Limitaciones conocidas

El análisis con expresiones regulares es una aproximación didáctica, no un analizador HTML.

Se admiten: mayúsculas/minúsculas, comillas dobles, simples o ausentes, comentarios HTML
(se ignoran), `data-src` (no se confunde con `src`) y la entidad `&amp;`.

**No se admiten:**

- `srcset`, `<picture>`/`<source>` y atributos de carga diferida (`data-src`, `loading`).
- Imágenes de fondo definidas en CSS (`background-image`) o en hojas de estilo externas.
- Contenido generado dinámicamente con JavaScript.
- La etiqueta `<base href>` (las referencias se resuelven contra la URL de la página).
- Etiquetas `<img>` con el carácter `>` dentro del valor de un atributo, o marcado malformado.

## Pruebas funcionales

Las pruebas se ejecutaron contra un servidor local incluido en el proyecto
(`pruebas/ServidorPruebas.java`, solo escucha en `localhost`). Para reproducirlas:

```bash
# Terminal 1: servidor de pruebas (puerto 8080)
java pruebas/ServidorPruebas.java

# Terminal 2: aplicación
java -cp out ImageDownloader http://localhost:8080/galeria.html
```

La página `/galeria.html` contiene referencias absolutas, relativas, repetidas, inexistentes,
un archivo que no es imagen, archivos de más de 10 MB (con y sin `Content-Length`), una
redirección, una imagen en un comentario HTML, un `data:` URI, una referencia vacía y un
atributo sin comillas.

| Caso | Entrada o condición | Resultado esperado | Resultado obtenido |
|---|---|---|---|
| P01 | Sin argumentos | Mostrar instrucciones de uso | ✅ Muestra `Uso: ...` |
| P02 | URL inválida | Error controlado | ✅ Mensaje de URL inválida |
| P03 | Protocolo no admitido | Rechazar dirección | ✅ Rechaza `ftp` y `file` |
| P04 | HTML sin imágenes | Reportar cero imágenes | ✅ `Imágenes encontradas: 0` |
| P05 | URL absoluta de imagen | Descargar correctamente | ✅ `logo.png` descargado |
| P06 | URL relativa de imagen | Resolver y descargar | ✅ `img/foto.jpg`, `../img/...` |
| P07 | Imagen duplicada | Descargar una sola vez | ✅ `Referencias duplicadas omitidas: 1` |
| P08 | Imagen inexistente | Registrar error y continuar | ✅ `Error HTTP 404` y continúa |
| P09 | Recurso que no es imagen | Rechazar descarga | ✅ `no es una imagen (text/html)` |
| P10 | Servidor no disponible | Controlar excepción | ✅ Mensaje controlado |
| P11 | Archivo mayor al límite | Cancelar descarga | ✅ Cancelada y sin archivo parcial |
| P12 | Archivo existente | Evitar sobrescritura | ✅ Se crean `imagen_001_2.png`, etc. |

### P01: sin argumentos

```text
$ java ImageDownloader 
Uso: java ImageDownloader <URL> [directorio]
[código de salida: 1]
```

### P02: URL inválida

```text
$ java ImageDownloader http://exa mple.com
ImageDownloader
---------------------------------------
URL inválida: sintaxis incorrecta (Illegal character in authority at index 10: http://exa mple.com)
[código de salida: 1]
```

```text
$ java ImageDownloader hola
ImageDownloader
---------------------------------------
URL inválida: falta el protocolo (use http:// o https://)
[código de salida: 1]
```

### P03: protocolo no admitido

```text
$ java ImageDownloader ftp://localhost/archivo.png
ImageDownloader
---------------------------------------
URL inválida: protocolo no admitido: ftp (solo http y https)
[código de salida: 1]
```

```text
$ java ImageDownloader file:///etc/passwd
ImageDownloader
---------------------------------------
URL inválida: protocolo no admitido: file (solo http y https)
[código de salida: 1]
```

### P04: HTML sin imágenes

```text
$ java ImageDownloader http://localhost:8080/vacia.html
ImageDownloader
---------------------------------------
Conectando con: http://localhost:8080/vacia.html

Código HTTP: 200
Tipo de contenido: text/html; charset=UTF-8

Analizando documento HTML...

Imágenes encontradas: 0

No hay imágenes que descargar.
[código de salida: 0]
```

### P05–P09 y P11: galería completa

```text
$ java ImageDownloader http://localhost:8080/galeria.html
ImageDownloader
---------------------------------------
Conectando con: http://localhost:8080/galeria.html

Código HTTP: 200
Tipo de contenido: text/html; charset=UTF-8

Analizando documento HTML...

Imágenes encontradas: 9
Referencias duplicadas omitidas: 1
Referencias ignoradas: 2
  - data:image/png;base64,iVBORw0KGgo= -> esquema no admitido: data:
  - (vacía) -> referencia vacía

[1/9] logo.png ............................. OK (imagen_001.png, 354 bytes)
[2/9] foto.jpg ............................. OK (imagen_002.jpg, 986 bytes)
[3/9] sin-comillas.gif ..................... OK (imagen_003.gif, 235 bytes)
[4/9] otra.png ............................. OK (imagen_004.png, 354 bytes)
[5/9] no-existe.png ........................ ERROR: Error HTTP 404
[6/9] falso.png ............................ ERROR: el recurso no es una imagen (text/html)
[7/9] enorme.png ........................... ERROR: supera el límite de 10 MB
[8/9] enorme-cl.png ........................ ERROR: supera el límite de 10 MB (Content-Length: 11534336)
[9/9] otra.png ............................. OK (imagen_009.png, 354 bytes)

---------------------------------------
Descargas exitosas: 5
Descargas fallidas: 4
Directorio: imagenes/
Registro: imagenes/descargas.csv
[código de salida: 0]
```

Observaciones sobre esta ejecución:

- `logo.png` aparece dos veces en el HTML (una absoluta y otra como `/img/logo.png`), pero
  se descarga una sola vez (**P07**).
- La imagen dentro del comentario HTML y `data-src="img/lazy.png"` se ignoran correctamente.
- La redirección `redir/otra.png` se sigue y se guarda como `imagen_004.png`.
- Las imágenes `enorme.png` (sin `Content-Length`, detectada por conteo de bytes) y
  `enorme-cl.png` (detectada por `Content-Length`) se cancelan; no queda ningún archivo parcial.
- La numeración respeta el orden de aparición, por eso no existen `imagen_005` a `imagen_008`.

### P10: servidor no disponible

```text
$ java ImageDownloader http://localhost:9999/index.html
ImageDownloader
---------------------------------------
Conectando con: http://localhost:9999/index.html

No se pudo obtener la página: no se pudo conectar (servidor no disponible o puerto cerrado)
[código de salida: 1]
```

Tiempo de espera agotado (servidor que tarda 20 s en responder; el límite de lectura es 15 s):

```text
$ java ImageDownloader http://localhost:8080/lento.html
ImageDownloader
---------------------------------------
Conectando con: http://localhost:8080/lento.html

No se pudo obtener la página: tiempo de espera agotado
[código de salida: 1]
(tardó 15 s)
```

### P12: archivo existente (segunda ejecución sobre el mismo directorio)

```text
$ java ImageDownloader http://localhost:8080/galeria.html
ImageDownloader
---------------------------------------
Conectando con: http://localhost:8080/galeria.html

Código HTTP: 200
Tipo de contenido: text/html; charset=UTF-8

Analizando documento HTML...

Imágenes encontradas: 9
Referencias duplicadas omitidas: 1
Referencias ignoradas: 2
  - data:image/png;base64,iVBORw0KGgo= -> esquema no admitido: data:
  - (vacía) -> referencia vacía

[1/9] logo.png ............................. OK (imagen_001_2.png, 354 bytes)
[2/9] foto.jpg ............................. OK (imagen_002_2.jpg, 986 bytes)
[3/9] sin-comillas.gif ..................... OK (imagen_003_2.gif, 235 bytes)
[4/9] otra.png ............................. OK (imagen_004_2.png, 354 bytes)
[5/9] no-existe.png ........................ ERROR: Error HTTP 404
[6/9] falso.png ............................ ERROR: el recurso no es una imagen (text/html)
[7/9] enorme.png ........................... ERROR: supera el límite de 10 MB
[8/9] enorme-cl.png ........................ ERROR: supera el límite de 10 MB (Content-Length: 11534336)
[9/9] otra.png ............................. OK (imagen_009_2.png, 354 bytes)

---------------------------------------
Descargas exitosas: 5
Descargas fallidas: 4
Directorio: imagenes/
Registro: imagenes/descargas.csv
[código de salida: 0]
```

Contenido de `imagenes/` tras las dos ejecuciones (los originales no se tocaron):

```text
descargas.csv
imagen_001.png
imagen_001_2.png
imagen_002.jpg
imagen_002_2.jpg
imagen_003.gif
imagen_003_2.gif
imagen_004.png
imagen_004_2.png
imagen_009.png
imagen_009_2.png
```

### Otros casos verificados

Referencias con `../` desde una subcarpeta y directorio personalizado (actividad complementaria):

```text
$ java ImageDownloader http://localhost:8080/sub/pagina.html ./descargas
ImageDownloader
---------------------------------------
Conectando con: http://localhost:8080/sub/pagina.html

Código HTTP: 200
Tipo de contenido: text/html; charset=UTF-8

Analizando documento HTML...

Imágenes encontradas: 2

[1/2] logo.png ............................. OK (imagen_001.png, 354 bytes)
[2/2] foto.jpg ............................. OK (imagen_002.jpg, 986 bytes)

---------------------------------------
Descargas exitosas: 2
Descargas fallidas: 0
Directorio: ./descargas/
Registro: ./descargas/descargas.csv
[código de salida: 0]
```

La página responde con error HTTP, o el recurso no es HTML:

```text
$ java ImageDownloader http://localhost:8080/no-existe.html
ImageDownloader
---------------------------------------
Conectando con: http://localhost:8080/no-existe.html

Código HTTP: 404
No se pudo obtener la página: Error HTTP 404
[código de salida: 1]
```

```text
$ java ImageDownloader http://localhost:8080/img/logo.png
ImageDownloader
---------------------------------------
Conectando con: http://localhost:8080/img/logo.png

Código HTTP: 200
Tipo de contenido: image/png
No se pudo obtener la página: el recurso no es un documento HTML (image/png)
[código de salida: 1]
```

## Actividad complementaria: `descargas.csv`

Cada ejecución genera `descargas.csv` en el directorio de destino (codificación UTF-8 con BOM,
para que Excel muestre bien los acentos). Ejemplo producido por la ejecución de `galeria.html`:

```csv
URL de imagen,Archivo local,Estado,Tamaño (bytes)
http://localhost:8080/img/logo.png,imagen_001.png,Correcto,354
http://localhost:8080/img/foto.jpg,imagen_002.jpg,Correcto,986
http://localhost:8080/img/sin-comillas.gif,imagen_003.gif,Correcto,235
http://localhost:8080/redir/otra.png,imagen_004.png,Correcto,354
http://localhost:8080/img/no-existe.png,—,Error HTTP 404,0
http://localhost:8080/img/falso.png,—,el recurso no es una imagen (text/html),0
http://localhost:8080/img/enorme.png,—,supera el límite de 10 MB,0
http://localhost:8080/img/enorme-cl.png,—,supera el límite de 10 MB (Content-Length: 11534336),0
http://localhost:8080/img/otra.png,imagen_009.png,Correcto,354
```

## Preguntas de reflexión

**1. ¿Qué responsabilidad desempeña la aplicación dentro de una arquitectura cliente-servidor?**
Es el *cliente*: inicia la comunicación, formula las solicitudes (HTTP GET), interpreta las
respuestas (código de estado, encabezados y contenido), procesa el HTML para descubrir qué
otros recursos necesita, los solicita y decide qué hacer ante errores o respuestas inesperadas.
El servidor solo responde a las solicitudes que recibe.

**2. ¿Qué diferencias existen entre `URL`, `URLConnection` y `HttpURLConnection`?**
`URL` *representa la localización* de un recurso (protocolo, servidor, puerto, ruta); por sí sola
no se comunica con nadie. `URLConnection` es la abstracción para *comunicarse* con el recurso
que apunta una `URL`: permite configurar la conexión (tiempos máximos, encabezados) y leer
metadatos y contenido, sin depender del protocolo. `HttpURLConnection` es su subclase
específica para HTTP: añade el método de la solicitud, el código de respuesta
(`getResponseCode()`), el manejo de redirecciones y errores HTTP. Para HTTPS se obtiene una
subclase suya (`HttpsURLConnection`).

**3. ¿Qué sucede al invocar `openConnection()`?**
Se crea y devuelve un objeto de conexión adecuado al protocolo de la URL, pero **todavía no
se establece comunicación con el servidor**. Esto permite configurarlo antes de conectar
(tiempos máximos, método, encabezados).

**4. ¿Qué operaciones provocan que la conexión se establezca realmente?**
`connect()` y, de forma implícita, las operaciones que necesitan datos del servidor:
`getInputStream()`, `getResponseCode()`, `getContentType()` y otros métodos que leen
encabezados de la respuesta. En esta aplicación la conexión se establece al llamar a
`getResponseCode()`.

**5. ¿Por qué una página HTML puede requerir múltiples conexiones?**
Porque una página es un documento HTML más una serie de recursos externos (imágenes, hojas de
estilo, scripts) referenciados por su propia URL, a veces en otros servidores. Cada recurso
requiere su propia solicitud, y por eso la aplicación abre una `URLConnection` para el HTML
y otra por cada imagen.

**6. ¿Cuál es la diferencia entre `getInputStream()` y `getContentType()`?**
`getInputStream()` entrega el *contenido* (el cuerpo de la respuesta) como un flujo de bytes
que hay que leer. `getContentType()` devuelve un *metadato*: el valor del encabezado
`Content-Type` (por ejemplo `image/png`), que describe qué tipo de contenido es sin necesidad
de leerlo. Se consulta primero para decidir cómo tratar el flujo.

**7. ¿Por qué distinguir recursos textuales y binarios?**
Un texto son bytes que deben decodificarse con una codificación (UTF-8, ISO-8859-1...) y se
leen con `Reader`. Un binario (una imagen) son bytes que deben conservarse exactos. Si una
imagen se lee con `BufferedReader`, la decodificación de caracteres altera o descarta bytes y
el archivo queda corrupto. Por eso las imágenes se leen con `InputStream` y se escriben tal
cual con `Files`/`OutputStream`.

**8. ¿Para qué sirven los tiempos máximos de conexión y lectura?**
Evitan que la aplicación se bloquee indefinidamente. El tiempo de conexión limita la espera
para establecer el enlace con el servidor; el de lectura limita la espera entre datos
recibidos (lanza `SocketTimeoutException`). Sin ellos, un servidor caído o muy lento dejaría
el programa colgado. Ver la prueba de tiempo de espera (15 s) más arriba.

**9. ¿Qué limitaciones tiene analizar HTML mediante expresiones regulares?**
El HTML no es un lenguaje regular: tiene anidamiento, atributos con distintas sintaxis,
comentarios, secciones `<script>` y marcado malformado que los navegadores toleran. Una
expresión como `<img\b[^>]*>` falla si el valor de un atributo contiene `>`; no entiende
`<base href>`, `srcset` ni `<picture>`; no ve contenido generado por JavaScript; y expresiones
complejas pueden provocar *backtracking* excesivo. Es suficiente como aproximación didáctica,
pero un analizador HTML real (que esta práctica prohíbe) es más robusto.

**10. ¿Cómo podría implementarse la descarga concurrente usando únicamente la API estándar de Java?**
Con `java.util.concurrent`: crear un `ExecutorService` (por ejemplo
`Executors.newFixedThreadPool(4)`, o desde Java 21 `Executors.newVirtualThreadPerTaskExecutor()`),
enviar una tarea `Callable<Resultado>` por imagen con `descargarImagen(...)` y recoger los
resultados con `invokeAll` o `Future.get()` (o con `CompletableFuture.supplyAsync(..., executor)`).
Hay que cuidar: limitar el número de hilos para no saturar al servidor, asignar la numeración
de los archivos antes de lanzar las tareas (la creación con `CREATE_NEW` ya evita colisiones),
recopilar los resultados en una estructura segura entre hilos, imprimir de forma ordenada y
cerrar el ejecutor con `shutdown()`.

### Reflexiones de las fases

- **¿Diferencia entre representar una dirección con `URL` y establecer una conexión?**
  `URL` solo describe dónde está el recurso; no genera tráfico. La conexión es la comunicación
  efectiva con el servidor y ocurre al invocar `connect()` o `getInputStream()`.
- **¿Qué significa HTTP 404 y qué pasa si la conexión funciona pero el servidor devuelve un error?**
  404 significa "recurso no encontrado". La comunicación sí se estableció, pero la respuesta
  indica fallo: por eso se comprueba `getResponseCode()` antes de leer el contenido, ya que
  `getInputStream()` lanzaría una excepción en códigos 4xx/5xx. La aplicación registra el error
  y continúa con la siguiente imagen.
- **¿Qué ocurriría si se intentara descargar `logo.png` sin considerar la URL base?**
  `new URL("logo.png")` lanza `MalformedURLException` (no hay protocolo ni servidor). Una
  referencia relativa solo tiene sentido respecto a la dirección de la página que la contiene.
