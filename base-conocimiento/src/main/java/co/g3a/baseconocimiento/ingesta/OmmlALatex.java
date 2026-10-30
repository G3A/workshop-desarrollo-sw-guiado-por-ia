package co.g3a.baseconocimiento.ingesta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Convierte una ecuación de Word (OMML, {@code <m:oMath>}) a LaTeX.
 *
 * <p>Port a Java de {@code docling/backend/docx/latex/omml.py} (MIT, The Docling Contributors), que
 * a su vez adapta {@code dwml} de xiilei (Apache-2.0). Misma estructura: un método por elemento
 * OMML, las propiedades ({@code *Pr}) como valores, y lo desconocido se ignora. La tabla de Unicode
 * a LaTeX reemplaza a {@code pylatexenc} con los símbolos matemáticos usuales; un carácter que no
 * está en ella se deja tal cual.
 */
final class OmmlALatex {

  static final String NS = "http://schemas.openxmlformats.org/officeDocument/2006/math";

  private OmmlALatex() {}

  static String convertir(Element oMath) {
    return hijos(oMath, null).replace("  ", " ").strip();
  }

  // ---------------------------------------------------------------- recorrido

  /** Propiedades de un elemento ({@code accPr}, {@code dPr}, ...): texto (saltos) y atributos. */
  private record Pr(String texto, Map<String, String> val) {
    String get(String k) {
      return val.get(k);
    }
  }

  private record Hijo(String tag, Object valor) {}

  private static List<Hijo> lista(Element e, List<String> incluir) {
    List<Hijo> res = new ArrayList<>();
    for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (!(n instanceof Element h) || !NS.equals(h.getNamespaceURI())) {
        continue;
      }
      String tag = h.getLocalName();
      if (incluir != null && !incluir.contains(tag)) {
        continue;
      }
      Object v = metodo(h, tag);
      if (v == null) {
        v = desconocido(h, tag);
      }
      if (v != null) {
        res.add(new Hijo(tag, v));
      }
    }
    return res;
  }

  private static Map<String, Object> dict(Element e, List<String> incluir) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (Hijo h : lista(e, incluir)) {
      m.put(h.tag(), h.valor());
    }
    return m;
  }

  private static String hijos(Element e, List<String> incluir) {
    StringBuilder sb = new StringBuilder();
    for (Hijo h : lista(e, incluir)) {
      sb.append(texto(h.valor()));
    }
    return sb.toString();
  }

  private static String texto(Object v) {
    return v == null ? "" : v instanceof Pr p ? p.texto() : (String) v;
  }

  private static String s(Map<String, Object> d, String k) {
    return texto(d.get(k));
  }

  private static Pr pr(Map<String, Object> d, String k) {
    return d.get(k) instanceof Pr p ? p : new Pr("", Map.of());
  }

  /** {@code box}, {@code num}, {@code den}, {@code deg}, {@code e} solo agrupan. */
  private static Object desconocido(Element e, String tag) {
    // borderBox y phant no están en el original: ahí su contenido se perdía.
    if (List.of("box", "num", "den", "deg", "e", "borderBox", "phant").contains(tag)) {
      return hijos(e, null);
    }
    if (tag.endsWith("Pr")) {
      return propiedades(e);
    }
    return null;
  }

  private static Pr propiedades(Element e) {
    Map<String, String> val = new LinkedHashMap<>();
    StringBuilder texto = new StringBuilder();
    for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (!(n instanceof Element h) || !NS.equals(h.getNamespaceURI())) {
        continue;
      }
      String tag = h.getLocalName();
      if (tag.equals("brk")) {
        texto.append(BRK);
      } else if (List.of("chr", "pos", "begChr", "endChr", "type").contains(tag)) {
        // m:val ausente es distinto de m:val="" (begChr vacío = sin delimitador).
        val.put(tag, h.hasAttributeNS(NS, "val") ? h.getAttributeNS(NS, "val") : null);
      }
    }
    return new Pr(texto.toString(), val);
  }

  private static Object metodo(Element e, String tag) {
    return switch (tag) {
      case "acc" -> acc(e);
      case "r" -> r(e);
      case "bar" -> bar(e);
      case "sSub" -> scripts(e, "sub", null, "sSubPr");
      case "sSup" -> scripts(e, null, "sup", "sSupPr");
      case "sSubSup" -> scripts(e, "sub", "sup", "sSubSupPr");
      case "sub" -> "_{" + hijos(e, null) + "}";
      case "sup" -> "^{" + hijos(e, null) + "}";
      case "f" -> f(e);
      case "func" -> func(e);
      case "fName" -> fName(e);
      case "groupChr" -> groupChr(e);
      case "d" -> d(e);
      case "rad" -> rad(e);
      case "eqArr" -> String.join(BRK, textos(lista(e, List.of("e"))));
      case "limLow" -> limLow(e);
      case "limUpp" ->
          "\\overset{"
              + s(dict(e, List.of("e", "lim")), "lim")
              + "}{"
              + s(dict(e, List.of("e", "lim")), "e")
              + "}";
      case "lim" -> lim(e);
      case "m" -> m(e);
      case "mr" -> String.join("&", textos(lista(e, List.of("e"))));
      case "nary" -> nary(e);
      default -> null;
    };
  }

  private static List<String> textos(List<Hijo> hs) {
    return hs.stream().map(h -> texto(h.valor())).toList();
  }

  // ---------------------------------------------------------------- elementos

  private static String acc(Element e) {
    var d = dict(e, null);
    String plantilla = valor(pr(d, "accPr").get("chr"), "\\hat{%s}", CHR);
    return plantilla.contains("%s") ? plantilla.replace("%s", s(d, "e")) : plantilla;
  }

  private static String bar(Element e) {
    var d = dict(e, null);
    Pr p = pr(d, "barPr");
    String plantilla = valor(p.get("pos"), "\\overline{%s}", POS);
    return p.texto() + (plantilla.contains("%s") ? plantilla.replace("%s", s(d, "e")) : plantilla);
  }

  private static String d(Element e) {
    var d = dict(e, null);
    Pr p = pr(d, "dPr");
    String ini = p.val().containsKey("begChr") ? p.get("begChr") : null;
    String fin = p.val().containsKey("endChr") ? p.get("endChr") : null;
    ini = ini == null ? "(" : T.getOrDefault(ini, ini);
    fin = fin == null ? ")" : T.getOrDefault(fin, fin);
    return p.texto()
        + "\\left"
        + (ini.isEmpty() ? "." : escapar(ini))
        + s(d, "e")
        + "\\right"
        + (fin.isEmpty() ? "." : escapar(fin));
  }

  private static String scripts(Element e, String sub, String sup, String prTag) {
    List<String> incl = new ArrayList<>(List.of("e", prTag));
    if (sub != null) {
      incl.add(sub);
    }
    if (sup != null) {
      incl.add(sup);
    }
    var d = dict(e, incl);
    String base = s(d, "e").stripTrailing();
    if (base.contains("\\frac") || base.contains("\\sqrt")) {
      base = "{" + base + "}";
    }
    StringBuilder sb = new StringBuilder(base);
    if (sub != null) {
      sb.append("_{").append(desenvolver(s(d, "sub"), "_")).append('}');
    }
    if (sup != null) {
      sb.append("^{").append(desenvolver(s(d, "sup"), "^")).append('}');
    }
    return sb.toString();
  }

  private static String desenvolver(String script, String marca) {
    String pre = marca + "{";
    return script.startsWith(pre) && script.endsWith("}")
        ? script.substring(pre.length(), script.length() - 1)
        : script;
  }

  private static String f(Element e) {
    var d = dict(e, null);
    String num = s(d, "num"), den = s(d, "den");
    Pr p = d.get("fPr") instanceof Pr x ? x : null;
    String tipo = p == null ? null : p.get("type");
    String plantilla = tipo == null ? "\\frac{N}{D}" : F.getOrDefault(tipo, "\\frac{N}{D}");
    return (p == null ? "" : p.texto()) + plantilla.replace("N", num).replace("D", den);
  }

  private static String func(Element e) {
    var d = dict(e, null);
    return s(d, "fName").replace(FE, s(d, "e"));
  }

  private static String fName(Element e) {
    StringBuilder sb = new StringBuilder();
    for (Hijo h : lista(e, null)) {
      String t = texto(h.valor());
      sb.append(h.tag().equals("r") ? FUNC.getOrDefault(t, t) : t);
    }
    String t = sb.toString();
    return t.contains(FE) ? t : t + FE;
  }

  private static String groupChr(Element e) {
    var d = dict(e, null);
    Pr p = pr(d, "groupChrPr");
    String plantilla = valor(p.get("chr"), "\\underbrace{%s}", CHR);
    return p.texto() + (plantilla.contains("%s") ? plantilla.replace("%s", s(d, "e")) : plantilla);
  }

  private static String rad(Element e) {
    var d = dict(e, null);
    String grado = s(d, "deg");
    return grado.isEmpty()
        ? "\\sqrt{" + s(d, "e") + "}"
        : "\\sqrt[" + grado + "]{" + s(d, "e") + "}";
  }

  private static String limLow(Element e) {
    var d = dict(e, List.of("e", "lim"));
    String base = s(d, "e"), lim = s(d, "lim");
    String f = LIM_FUNC.get(base);
    return f != null ? f.replace("%s", lim) : base + "_{" + lim + "}";
  }

  private static String lim(Element e) {
    String crudo = hijos(e, null).replace("\\rightarrow", "\\to").stripTrailing();
    final String r =
        crudo.endsWith(BRK) ? crudo.substring(0, crudo.length() - 2).stripTrailing() : crudo;
    if (!r.isEmpty() && "\\<>=+*/^_{}".chars().noneMatch(c -> r.indexOf(c) >= 0)) {
      return r.replace(" ", "\\ ");
    }
    return r;
  }

  private static String m(Element e) {
    List<String> filas = new ArrayList<>();
    for (Hijo h : lista(e, null)) {
      if (h.tag().equals("mr")) {
        filas.add(texto(h.valor()));
      }
    }
    return "\\begin{matrix}" + String.join(BRK, filas) + "\\end{matrix}";
  }

  private static String nary(Element e) {
    String op = "";
    StringBuilder resto = new StringBuilder();
    for (Hijo h : lista(e, null)) {
      if (h.tag().equals("naryPr")) {
        op = valor(((Pr) h.valor()).get("chr"), "\\int", CHR_BO);
      } else {
        resto.append(texto(h.valor()));
      }
    }
    return op + resto;
  }

  /** {@code <m:r>}: el texto de su {@code <m:t>}, símbolo por símbolo a LaTeX. */
  private static String r(Element e) {
    String t = null;
    for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element h
          && NS.equals(h.getNamespaceURI())
          && h.getLocalName().equals("t")) {
        t = h.getTextContent();
        break;
      }
    }
    if (t == null || t.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    t.codePoints().forEach(cp -> sb.append(simbolo(cp)));
    String res = escapar(sb.toString());
    // Las llaves que trae la tabla (\mathbb{R}) no son texto: solo se escapan las del original.
    if (t.indexOf('{') < 0) {
      res = res.replace("\\{", "{");
    }
    if (t.indexOf('}') < 0) {
      res = res.replace("\\}", "}");
    }
    // ^ dentro de <m:r> es un operador matemático, no un carácter de texto a escapar.
    if (t.indexOf('^') >= 0) {
      res = res.replace("\\^", "^");
    }
    return res;
  }

  private static String simbolo(int cp) {
    String s = new String(Character.toChars(cp));
    String m = MATEMATICOS.get(s);
    if (m != null) {
      return m;
    }
    m = T.get(s);
    return m != null ? m : s;
  }

  private static String escapar(String s) {
    StringBuilder sb = new StringBuilder();
    char previo = 0;
    for (char c : s.replace("\\\\", "\\").toCharArray()) {
      if ("{}_^#&$%~".indexOf(c) >= 0 && previo != '\\') {
        sb.append('\\');
      }
      sb.append(c);
      previo = c;
    }
    return sb.toString();
  }

  private static String valor(String clave, String porDefecto, Map<String, String> tabla) {
    return clave == null ? porDefecto : tabla.getOrDefault(clave, clave);
  }

  // ---------------------------------------------------------------- tablas (latex_dict.py)

  private static final String BRK = "\\\\";
  private static final String FE = "{fe}";

  private static final Map<String, String> POS =
      Map.of("top", "\\overline{%s}", "bot", "\\underline{%s}");

  private static final Map<String, String> F =
      Map.of(
          "bar",
          "\\frac{N}{D}",
          "skw",
          "^{N}/_{D}",
          "noBar",
          "\\genfrac{}{}{0pt}{}{N}{D}",
          "lin",
          "{N}/{D}");

  private static final Map<String, String> LIM_FUNC =
      Map.of(
          "lim",
          "\\lim_{%s}",
          "max",
          "\\max_{%s}",
          "min",
          "\\min_{%s}",
          "argmax",
          "\\operatorname{argmax}_{%s}",
          "argmin",
          "\\operatorname{argmin}_{%s}");

  private static final Map<String, String> CHR =
      Map.ofEntries(
          Map.entry("\u0300", "\\grave{%s}"), Map.entry("\u0301", "\\acute{%s}"),
          Map.entry("\u0302", "\\hat{%s}"), Map.entry("\u0303", "\\tilde{%s}"),
          Map.entry("\u0304", "\\bar{%s}"), Map.entry("\u0305", "\\overbar{%s}"),
          Map.entry("\u0306", "\\breve{%s}"), Map.entry("\u0307", "\\dot{%s}"),
          Map.entry("\u0308", "\\ddot{%s}"), Map.entry("\u030c", "\\check{%s}"),
          Map.entry("\u0338", "\\not{%s}"), Map.entry("\u20d6", "\\overleftarrow{%s}"),
          Map.entry("\u20d7", "\\vec{%s}"), Map.entry("\u20db", "\\dddot{%s}"),
          Map.entry("\u20e1", "\\overleftrightarrow{%s}"), Map.entry("\u0331", "\\underbar{%s}"),
          Map.entry("\u23b4", "\\overbracket{%s}"), Map.entry("\u23dc", "\\overparen{%s}"),
          Map.entry("\u23de", "\\overbrace{%s}"), Map.entry("\u23b5", "\\underbracket{%s}"),
          Map.entry("\u23dd", "\\underparen{%s}"), Map.entry("\u23df", "\\underbrace{%s}"));

  private static final Map<String, String> CHR_BO =
      Map.ofEntries(
          Map.entry("\u220f", "\\prod"), Map.entry("\u2210", "\\coprod"),
          Map.entry("\u2211", "\\sum"), Map.entry("\u222b", "\\int"),
          Map.entry("\u222c", "\\iint"), Map.entry("\u222d", "\\iiint"),
          Map.entry("\u222e", "\\oint"), Map.entry("\u222f", "\\oiint"),
          Map.entry("\u2230", "\\oiiint"), Map.entry("\u22c0", "\\bigwedge"),
          Map.entry("\u22c1", "\\bigvee"), Map.entry("\u22c2", "\\bigcap"),
          Map.entry("\u22c3", "\\bigcup"), Map.entry("\u2a00", "\\bigodot"),
          Map.entry("\u2a01", "\\bigoplus"), Map.entry("\u2a02", "\\bigotimes"));

  private static final Map<String, String> FUNC =
      Map.ofEntries(
          Map.entry("sin", "\\sin({fe})"), Map.entry("cos", "\\cos({fe})"),
          Map.entry("tan", "\\tan({fe})"), Map.entry("arcsin", "\\arcsin({fe})"),
          Map.entry("arccos", "\\arccos({fe})"), Map.entry("arctan", "\\arctan({fe})"),
          Map.entry("sinh", "\\sinh({fe})"), Map.entry("cosh", "\\cosh({fe})"),
          Map.entry("tanh", "\\tanh({fe})"), Map.entry("coth", "\\coth({fe})"),
          Map.entry("sec", "\\sec({fe})"), Map.entry("csc", "\\csc({fe})"),
          Map.entry("mod", "\\mod {fe}"), Map.entry("max", "\\max({fe})"),
          Map.entry("min", "\\min({fe})"), Map.entry("log", "\\log({fe})"),
          Map.entry("ln", "\\ln({fe})"), Map.entry("exp", "\\exp({fe})"),
          Map.entry("det", "\\det({fe})"), Map.entry("gcd", "\\gcd({fe})"),
          Map.entry("deg", "\\deg({fe})"), Map.entry("ker", "\\ker({fe})"),
          Map.entry("dim", "\\dim({fe})"), Map.entry("arg", "\\arg({fe})"),
          Map.entry("inf", "\\inf({fe})"), Map.entry("sup", "\\sup({fe})"),
          Map.entry("lim", "\\lim({fe})"), Map.entry("Pr", "\\Pr({fe})"));

  /** Letras itálicas matemáticas (U+1D434...) a su letra normal; flechas y relaciones. */
  private static final Map<String, String> T = new java.util.HashMap<>();

  /** Lo que en Docling hace pylatexenc, para los símbolos matemáticos usuales. */
  private static final Map<String, String> MATEMATICOS = new java.util.HashMap<>();

  static {
    for (int i = 0; i < 26; i++) {
      T.put(new String(Character.toChars(0x1D434 + i)), String.valueOf((char) ('A' + i)));
      // U+1D455 (h itálica) no existe: Unicode usa U+210E.
      if (i != 7) {
        T.put(new String(Character.toChars(0x1D44E + i)), String.valueOf((char) ('a' + i)));
      }
    }
    String[][] t = {
      {"\u2190", "\\leftarrow "},
      {"\u2191", "\\uparrow "},
      {"\u2192", "\\rightarrow "},
      {"\u2193", "\\downarrow "},
      {"\u2194", "\\leftrightarrow "},
      {"\u22ee", "\\vdots "},
      {"\u22ef", "\\cdots "},
      {"\u22f1", "\\ddots "},
      {"\u2260", "\\ne "},
      {"\u2264", "\\leq "},
      {"\u2265", "\\geq "},
      {"\u226a", "\\ll "},
      {"\u226b", "\\gg "},
      {"\u2208", "\\in "},
      {"\u2209", "\\notin "},
      {"\u220b", "\\ni "},
      {"\u221e", "\\infty "},
      {"\u00b1", "\\pm "},
      {"\u2213", "\\mp "},
    };
    for (String[] p : t) {
      T.put(p[0], p[1]);
    }

    String[] griegasMin = {
      "alpha",
      "beta",
      "gamma",
      "delta",
      "epsilon",
      "zeta",
      "eta",
      "theta",
      "iota",
      "kappa",
      "lambda",
      "mu",
      "nu",
      "xi",
      "omicron",
      "pi",
      "rho",
      "varsigma",
      "sigma",
      "tau",
      "upsilon",
      "phi",
      "chi",
      "psi",
      "omega"
    };
    for (int i = 0; i < griegasMin.length; i++) {
      MATEMATICOS.put(String.valueOf((char) (0x03B1 + i)), "\\" + griegasMin[i] + " ");
    }
    String[][] m = {
      {"\u0393", "\\Gamma "},
      {"\u0394", "\\Delta "},
      {"\u0398", "\\Theta "},
      {"\u039B", "\\Lambda "},
      {"\u039E", "\\Xi "},
      {"\u03A0", "\\Pi "},
      {"\u03A3", "\\Sigma "},
      {"\u03A5", "\\Upsilon "},
      {"\u03A6", "\\Phi "},
      {"\u03A8", "\\Psi "},
      {"\u03A9", "\\Omega "},
      {"\u03D5", "\\phi "},
      {"\u03F5", "\\epsilon "},
      {"\u03D1", "\\vartheta "},
      {"\u2013", "-"},
      {"\u2014", "-"},
      {"\u2212", "-"},
      {"\u00d7", "\\times "},
      {"\u00f7", "\\div "},
      {"\u00b7", "\\cdot "},
      {"\u22c5", "\\cdot "},
      {"\u2248", "\\approx "},
      {"\u2261", "\\equiv "},
      {"\u221d", "\\propto "},
      {"\u2202", "\\partial "},
      {"\u2207", "\\nabla "},
      {"\u2200", "\\forall "},
      {"\u2203", "\\exists "},
      {"\u2205", "\\emptyset "},
      {"\u2282", "\\subset "},
      {"\u2283", "\\supset "},
      {"\u2286", "\\subseteq "},
      {"\u2287", "\\supseteq "},
      {"\u222a", "\\cup "},
      {"\u2229", "\\cap "},
      {"\u2227", "\\wedge "},
      {"\u2228", "\\vee "},
      {"\u00ac", "\\neg "},
      {"\u21d2", "\\Rightarrow "},
      {"\u21d4", "\\Leftrightarrow "},
      {"\u2218", "\\circ "},
      {"\u2032", "'"},
      {"\u221a", "\\surd "},
      {"\u210f", "\\hbar "},
      {"\u2113", "\\ell "},
      {"\u211d", "\\mathbb{R}"},
      {"\u2115", "\\mathbb{N}"},
      {"\u2124", "\\mathbb{Z}"},
      {"\u211a", "\\mathbb{Q}"},
      {"\u2102", "\\mathbb{C}"},
      {"\u210e", "h"},
      {"\u2026", "\\ldots "},
    };
    for (String[] p : m) {
      MATEMATICOS.put(p[0], p[1]);
    }
  }
}
