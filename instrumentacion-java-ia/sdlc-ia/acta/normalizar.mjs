// Normalizacion de rutas del acta: la raiz del repo pasa a "." y la carpeta del usuario a "~".
//
// Un acta que guarda D:\GitHub_public\... no se compara con la misma sesion compilada en otra
// maquina, y ademas publica el nombre del usuario. La raiz llega en cuatro formas segun quien la
// escribio: Windows (D:\x), Windows con barras (D:/x), Git Bash (/d/x) y JSON escapado dentro de
// un resultado (D:\\x). Se reemplazan las cuatro, con la letra de unidad en cualquier caja.

const escapar = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

function variantesDeRaiz(raiz) {
  const partes = raiz.replace(/\\/g, '/').replace(/\/+$/, '').split('/');
  const unidad = /^[A-Za-z]:$/.test(partes[0]) ? partes[0][0] : null;
  const resto = (unidad ? partes.slice(1) : partes).filter(Boolean).map(escapar);
  if (!unidad) return [new RegExp('/' + resto.join('/'), 'g')];
  const u = `[${unidad.toLowerCase()}${unidad.toUpperCase()}]`;
  return [
    new RegExp(`${u}:\\\\\\\\${resto.join('\\\\\\\\')}`, 'g'),
    new RegExp(`${u}:\\\\${resto.join('\\\\')}`, 'g'),
    new RegExp(`${u}:/${resto.join('/')}`, 'g'),
    new RegExp(`/${u}/${resto.join('/')}`, 'g'),
  ];
}

const CASA = [
  /[A-Za-z]:\\\\Users\\\\[^\\/\s"'<>]+/g,
  /[A-Za-z]:\\Users\\[^\\/\s"'<>]+/g,
  /[A-Za-z]:\/Users\/[^\\/\s"'<>]+/g,
  /\/[a-z]\/Users\/[^\\/\s"'<>]+/g,
  /\/home\/[^\\/\s"'<>]+/g,
];

// El valor de una variable cuyo nombre dice que es secreta, en la forma NOMBRE=valor de un `env`
// o de un comando (#222, fase 3). Desde que el acta se versiona, lo que entra en ella se publica, y
// un `env` dentro de una sesion de Claude Code muestra CLAUDE_CODE_MESSAGING_TOKEN. gitleaks no
// siempre lo reconoce, porque no tiene la forma de ninguna credencial conocida.
const SECRETO = /\b((?:[A-Z][A-Z0-9_]*)?(?:TOKEN|SECRET|PASSWORD|PASSWD|API_KEY|APIKEY|PRIVATE_KEY)[A-Z0-9_]*)=(?!<redactado>)[^\s"'`\\]+/g;

// Los campos que llevan contenido de archivos: lo que una edicion escribe y el diff de un cambio.
// En un archivo del repo solo se normaliza la raiz: redactar la identidad, los secretos o la
// carpeta del perfil romperia la verificacion -- la curacion y el motor vuelven a aplicar cada
// Edit sobre el blob real; una prueba que citaba `C:/Users/ANAPER~1` se detenia (#255) -- y no
// protegeria nada: ese contenido lo publica el commit, y los blobs viajan en el pack de objetos.
// En un archivo de fuera del repo, como el scratchpad, ningun commit lo publica y nada lo
// verifica: ahi se redacta todo.
const CONTENIDO = new Set(['content', 'old_string', 'new_string', 'new_source', 'diff']);
const ABSOLUTA = /^(?:[A-Za-z]:[\\/]|[\\/~])/;

// La identidad de quien trabajo (#222, fase 3): su cuenta del sistema, su dominio y su email de
// git no entran al acta, que se publica. Cada valor se cambia por <usuario> o <email>, sin
// distinguir mayusculas; `cortos` son expresiones, para el nombre 8.3 de su carpeta (ANAPER~1).
// Cada signo admite barras delante: un grep que busca la identidad la escribe como regex
// escapada (`ana\.perez`), y asi tambien se publicaba (#253).
const conEscapes = (x) => [...x]
  .map((c) => (/[\p{L}\p{N}]/u.test(c) ? escapar(c) : `\\\\*${escapar(c)}`)).join('');

// Con `publica` (el usuario de GitHub y su noreply), cada valor se cambia por ella en vez de por
// <usuario> o <email>: el acta dice quien trabajo sin publicar su cuenta ni su email personal.
function deIdentidad({ literales = [], cortos = [], publica = null } = {}) {
  const usuario = publica?.usuario ?? '<usuario>';
  const email = publica?.email ?? '<email>';
  const res = [...new Set(literales.filter((x) => x && x.length >= 4))]
    .sort((a, b) => b.length - a.length)
    .map((x) => [new RegExp(conEscapes(x), 'giu'), () => (x.includes('@') ? email : usuario)]);
  return [...res, ...cortos.map((re) => [re, () => usuario])];
}

export function normalizador(raiz, { identidad = {} } = {}) {
  const deRaiz = raiz ? variantesDeRaiz(raiz) : [];
  const identidades = deIdentidad(identidad);
  const raizARelativa = (s) => {
    let r = s;
    for (const re of deRaiz) r = r.replace(re, '.');
    return r;
  };
  const rutas = (s) => {
    let r = raizARelativa(s);
    for (const re of CASA) r = r.replace(re, '~');
    return r;
  };
  const texto = (s) => {
    let r = rutas(s).replace(SECRETO, '$1=<redactado>');
    for (const [re, por] of identidades) r = r.replace(re, por);
    return r;
  };
  const enElRepo = (ruta) => !ABSOLUTA.test(raizARelativa(ruta));
  const profundo = (v, clave = null, fuera = false) => {
    if (typeof v === 'string') return CONTENIDO.has(clave) && !fuera ? raizARelativa(v) : texto(v);
    if (Array.isArray(v)) return v.map((x) => profundo(x, clave, fuera));
    if (v && typeof v === 'object') {
      const ruta = v.file_path ?? v.notebook_path;
      const deFuera = typeof ruta === 'string' ? !enElRepo(ruta) : fuera;
      const o = {};
      for (const k of Object.keys(v)) o[k] = profundo(v[k], k, deFuera);
      return o;
    }
    return v;
  };
  return { texto, profundo };
}

// La identidad del entorno donde se compila: la que el hook y registrar-sesion.mjs redactan.
// `extra` es lo que el entorno no puede saber, como un email personal que no es el de git: llega
// por ACTA_REDACTAR. El dominio tambien va solo, no unicamente pegado a la cuenta (#253).
//
// Si el email de git es el noreply de GitHub (`<id>+<usuario>@users.noreply.github.com`), esa es
// la identidad publica de quien trabajo: no se redacta, y lo demas se cambia por ella (#255).
const NOREPLY = /^(?:\d+\+)?([A-Za-z0-9-]+)@users\.noreply\.github\.com$/i;

export function identidadDelEntorno({ email = null, usuario = null, dominio = null,
  casa = null, extra = [] } = {}) {
  const deGitHub = email ? NOREPLY.exec(email) : null;
  const publica = deGitHub ? { usuario: deGitHub[1], email } : null;
  const literales = [publica ? null : email, usuario, casa, dominio, ...extra];
  if (dominio && usuario) literales.push(`${dominio}+${usuario}`, `${dominio}\\${usuario}`);
  const corto = casa ? casa.replace(/[^A-Za-z0-9]/g, '').slice(0, 6) : '';
  return { literales, publica,
    cortos: corto.length >= 4 ? [new RegExp(`\\b${corto}~[1-9]\\b`, 'gi')] : [] };
}
