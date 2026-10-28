"use strict";

// Pruebas del parser de bloques de codigo del chat (issue #54). Corren con
// `node --test` sin dependencias: codigo.js es un modulo puro, sin DOM.
const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const STATIC = path.join(__dirname, "..", "..", "main", "resources", "static");
const { partir, dividirEnLineas, primerSegmentoDistinto, detectarLenguaje } = require(path.join(STATIC, "codigo.js"));

const texto = (t) => ({ tipo: "texto", texto: t });
const codigo = (lenguaje, c, cerrado) => ({ tipo: "codigo", lenguaje: lenguaje, codigo: c, cerrado: cerrado });

test("sin cercas todo es texto", () => {
  assert.deepEqual(partir("Hola\nmundo", false), [texto("Hola\nmundo")]);
});

test("texto vacio no da segmentos", () => {
  assert.deepEqual(partir("", true), []);
});

test("un bloque con lenguaje queda entre dos textos, sin los saltos que rodean las cercas", () => {
  assert.deepEqual(
    partir("Mira:\n```java\nint a = 1;\n```\nListo.", false),
    [texto("Mira:"), codigo("java", "int a = 1;", true), texto("Listo.")]);
});

test("las lineas en blanco que rodean un bloque no quedan en el texto, de ningun lado", () => {
  assert.deepEqual(
    partir("Mira:\n\n```js\na\n```\n\nListo.", true),
    [texto("Mira:"), codigo("js", "a", true), texto("Listo.")]);
  assert.deepEqual(partir("Uno\n\nDos", true), [texto("Uno\n\nDos")]);
});

test("un bloque sin lenguaje", () => {
  assert.deepEqual(partir("```\nmake up\n```\n", false), [codigo("", "make up", true)]);
});

test("varios bloques y lineas vacias dentro del codigo", () => {
  assert.deepEqual(
    partir("```sql\nSELECT 1;\n\nSELECT 2;\n```\ny\n```bash\nls\n```\n", false),
    [codigo("sql", "SELECT 1;\n\nSELECT 2;", true), texto("y"), codigo("bash", "ls", true)]);
});

test("un bloque sin cerrar sigue abierto mientras llega el stream", () => {
  assert.deepEqual(partir("Va:\n```js\nconst a = 1;\nconst b", false),
    [texto("Va:"), codigo("js", "const a = 1;\nconst b", false)]);
});

test("un bloque sin cerrar cuenta como cerrado cuando el turno termino", () => {
  assert.deepEqual(partir("```js\nconst a = 1;", true), [codigo("js", "const a = 1;", true)]);
});

test("una apertura sin salto de linea abre el bloque, pero sin lenguaje todavia", () => {
  assert.deepEqual(partir("Mira:\n```jav", false), [texto("Mira:"), codigo("", "", false)]);
});

test("un cierre sin salto de linea no cierra mientras llega el stream (puede ser ```js)", () => {
  assert.deepEqual(partir("```\na\n```", false), [codigo("", "a", false)]);
  assert.deepEqual(partir("```\na\n```", true), [codigo("", "a", true)]);
});

test("backticks sueltos al final de un bloque abierto no se muestran hasta saber que son", () => {
  assert.deepEqual(partir("```js\na\n``", false), [codigo("js", "a", false)]);
  assert.deepEqual(partir("Hola\n`", false), [texto("Hola")]);
  assert.deepEqual(partir("Hola\n`", true), [texto("Hola\n`")]);
});

test("una cerca de 4 backticks contiene ``` sin cerrarse", () => {
  assert.deepEqual(partir("````md\n```\nx\n```\n````\n", false), [codigo("md", "```\nx\n```", true)]);
});

test("la sangria de la apertura se quita de cada linea del codigo", () => {
  assert.deepEqual(partir("  ```py\n  if a:\n      b()\n  ```\n", false),
    [codigo("py", "if a:\n    b()", true)]);
});

test("cuatro espacios de sangria no son una cerca", () => {
  assert.deepEqual(partir("    ```\nx", true), [texto("    ```\nx")]);
});

test("CRLF se normaliza a LF", () => {
  assert.deepEqual(partir("a\r\n```java\r\nint x;\r\n```\r\n", false), [texto("a"), codigo("java", "int x;", true)]);
});

test("el lenguaje es la primera palabra del info string, en minusculas", () => {
  assert.deepEqual(partir("``` Java title=X.java\nx\n```\n", false), [codigo("java", "x", true)]);
});

test("un info string hostil no llega como lenguaje", () => {
  assert.deepEqual(partir("```\"><img src=x onerror=alert(1)>\nx\n```\n", false), [codigo("", "x", true)]);
});

// Quita las etiquetas y decodifica las entidades que escribe highlight.js:
// si dividirEnLineas perdiera o duplicara texto, esto no daria el codigo.
function textoPlano(html) {
  return html.replace(/<[^>]*>/g, "")
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"')
    .replace(/&#x27;/g, "'").replace(/&#39;/g, "'").replace(/&amp;/g, "&");
}

function balanceado(linea) {
  const abiertos = (linea.match(/<span\b/g) || []).length;
  const cerrados = (linea.match(/<\/span>/g) || []).length;
  return abiertos === cerrados;
}

test("dividirEnLineas reabre en cada linea los spans que la cruzan", () => {
  const html = 'int a;\n<span class="hljs-comment">/* uno\ndos */</span>\n' +
    '<span class="hljs-string">&quot;a &lt;&amp;&gt;\n<span class="hljs-subst">${b}</span>\n&#x27;c&#x27;&quot;</span>';
  const lineas = dividirEnLineas(html);
  assert.equal(lineas.length, 6);
  assert.equal(lineas[1], '<span class="hljs-comment">/* uno</span>');
  assert.equal(lineas[2], '<span class="hljs-comment">dos */</span>');
  assert.equal(lineas[4], '<span class="hljs-string"><span class="hljs-subst">${b}</span></span>');
  lineas.forEach((l) => assert.ok(balanceado(l), l));
  assert.equal(lineas.map(textoPlano).join("\n"), "int a;\n/* uno\ndos */\n\"a <&>\n${b}\n'c'\"");
});

test("dividirEnLineas conserva las lineas vacias y la vacia final", () => {
  assert.deepEqual(dividirEnLineas("a\n\nb\n"), ["a", "", "b", ""]);
  assert.deepEqual(dividirEnLineas(""), [""]);
});

test("primerSegmentoDistinto da el indice desde el que hay que repintar", () => {
  const a = [texto("x"), codigo("js", "a", false)];
  assert.equal(primerSegmentoDistinto(a, [texto("x"), codigo("js", "a", false)]), 2);
  assert.equal(primerSegmentoDistinto(a, [texto("x"), codigo("js", "ab", false)]), 1);
  assert.equal(primerSegmentoDistinto(a, [texto("x"), codigo("js", "a", true)]), 1);
  assert.equal(primerSegmentoDistinto(a, [texto("y")]), 0);
  assert.equal(primerSegmentoDistinto([], a), 0);
  assert.equal(primerSegmentoDistinto(a, [texto("x"), codigo("js", "a", false), texto("z")]), 2);
});

// El highlight.js vendorizado, cargado como lo carga la pagina (un script que
// declara la global hljs), para probar la deteccion contra la libreria real.
function cargarHljs() {
  const vm = require("node:vm");
  const contexto = {};
  vm.createContext(contexto);
  const fuente = fs.readFileSync(path.join(STATIC, "vendor", "highlight", "highlight.min.js"), "utf8");
  vm.runInContext(fuente + ";this.hljs = hljs;", contexto);
  return contexto.hljs;
}

test("detectarLenguaje acierta con lo que mas aparece en esta base de conocimiento", () => {
  const hljs = cargarHljs();
  const detectado = (c) => detectarLenguaje(hljs, c).lenguaje;
  // Con los 36 lenguajes de la build, highlightAuto daba css para este SQL.
  assert.equal(detectado("SELECT q.question, q.advertencias\n  FROM query_log q\n WHERE q.advertencias IS NOT NULL;"), "sql");
  assert.equal(detectado("services:\n  api:\n    image: kb\n    ports:\n      - 8080:8080"), "yaml");
  assert.equal(detectado('{\n  "a": 1,\n  "b": [true, null]\n}'), "json");
  assert.equal(detectado("<dependency>\n  <groupId>org.x</groupId>\n</dependency>"), "xml");
  assert.equal(detectado("@Service\nclass Consultar {\n  private final JdbcClient jdbc;\n  Consultar(JdbcClient jdbc) { this.jdbc = jdbc; }\n}"), "java");
});

test("detectarLenguaje prefiere no decir nada antes que equivocarse", () => {
  const hljs = cargarHljs();
  const prosa = detectarLenguaje(hljs, "Primero levanta el stack y despues ingesta <b>.");
  assert.equal(prosa.lenguaje, null);
  assert.equal(prosa.html, "Primero levanta el stack y despues ingesta &lt;b&gt;.");
  // Relevancia 1: con los 36 lenguajes salia java.
  assert.equal(detectarLenguaje(hljs, "CREATE TABLE documento (\n  id BIGSERIAL PRIMARY KEY\n);").lenguaje, null);
});

test("la pagina no carga nada por la red: la app es 100 % local", () => {
  const html = fs.readFileSync(path.join(STATIC, "index.html"), "utf8");
  const externos = html.match(/<(?:script|link)\b[^>]*\b(?:src|href)\s*=\s*["']?(?:https?:)?\/\//gi) || [];
  assert.deepEqual(externos, []);
  assert.ok(fs.existsSync(path.join(STATIC, "vendor", "highlight", "highlight.min.js")), "falta el highlight.js vendorizado");
});
