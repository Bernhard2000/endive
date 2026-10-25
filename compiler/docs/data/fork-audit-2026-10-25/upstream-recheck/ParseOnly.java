public final class ParseOnly { public static void main(String[] a) throws Exception {
  try { run.endive.wasm.Parser.parse(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(a[0]))); System.out.println(a[0]+": valid"); }
  catch (Exception e) { System.out.println(a[0]+": "+e.getClass().getSimpleName()+": "+e.getMessage()); } } }
