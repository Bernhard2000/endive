import java.nio.file.Files;
import java.nio.file.Path;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.experimental.dircache.DirectoryCache;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
public class CacheProbe {
  public static void main(String[] args) throws Exception {
    var module = Parser.parse(Files.readAllBytes(Path.of(args[0])));
    for (int bits=0; bits<4; bits++) {
      boolean unsigned=(bits&1)!=0, branchless=(bits&2)!=0;
      try {
        var factory=MachineFactoryCompiler.builder(module)
            .withUnsignedCompareFusion(unsigned).withBranchlessBooleans(branchless)
            .withCache(new DirectoryCache(Path.of(args[1]))).compile();
        Instance.builder(module).withMachineFactory(factory).build();
        System.out.println("unsigned="+unsigned+" branchless="+branchless+" OK");
      } catch(Exception e) {
        System.out.println("unsigned="+unsigned+" branchless="+branchless+" "+e);
      }
    }
    try {
      MachineFactoryCompiler.builder(module).withCache(new DirectoryCache(Path.of(args[1]))).compile();
      System.out.println("runtime defaults OK");
    } catch(Exception e) { System.out.println("runtime defaults "+e); }
  }
}
