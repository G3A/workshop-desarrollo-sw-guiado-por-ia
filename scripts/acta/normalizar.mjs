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

export function normalizador(raiz) {
  const deRaiz = raiz ? variantesDeRaiz(raiz) : [];
  const texto = (s) => {
    let r = s;
    for (const re of deRaiz) r = r.replace(re, '.');
    for (const re of CASA) r = r.replace(re, '~');
    return r;
  };
  const profundo = (v) => {
    if (typeof v === 'string') return texto(v);
    if (Array.isArray(v)) return v.map(profundo);
    if (v && typeof v === 'object') {
      const o = {};
      for (const k of Object.keys(v)) o[k] = profundo(v[k]);
      return o;
    }
    return v;
  };
  return { texto, profundo };
}
