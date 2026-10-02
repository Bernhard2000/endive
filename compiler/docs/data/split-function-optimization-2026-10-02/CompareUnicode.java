import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import run.endive.runtime.Memory;
import run.endive.runtime.Instance;
import org.objectweb.asm.*;
public class CompareUnicode {
  static class DumpLoader extends ClassLoader {
    private final Path dir;
    DumpLoader(Path dir) { super(CompareUnicode.class.getClassLoader()); this.dir = dir; }
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      try { var file=dir.resolve(name+".class"); if(!Files.exists(file)) file=dir.resolve(name.replace('.','_')+".class"); byte[] bytes=Files.readAllBytes(file); return defineClass(name,bytes,0,bytes.length); }
      catch (java.io.IOException ex) { throw new ClassNotFoundException(name,ex); }
    }
  }
  static Method function(String dir) throws Exception {
    var path=Path.of(dir);
    byte[] bytes=Files.readAllBytes(path.resolve("run.endive.$gen.CompiledMachineFuncGroup_0.class"));
    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
      public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
        if (!name.equals("_PyUnicode_ToNumeric_3281")) return null;
        return new MethodVisitor(Opcodes.ASM9) {
          public void visitMethodInsn(int opcode,String owner,String name,String desc,boolean itf) {
            System.out.println("numeric callee "+owner+"."+name+desc);
          }
        };
      }
    },0);
    var method = Class.forName("run.endive.$gen.CompiledMachineFuncGroup_0",true,new DumpLoader(path))
       .getMethod("_PyUnicode_ToNumeric_3281",int.class,Memory.class,Instance.class);
    method.setAccessible(true);
    return method;
  }
  public static void main(String[] args) throws Exception {
    var before=function(args[0]);var after=function(args[1]); int count=0;
    for(int i=0;i<=0x10ffff;i++) { check(before,after,i);count++; }
    for(int i:new int[]{-1,-2,Integer.MIN_VALUE,Integer.MAX_VALUE,0x110000,0x1fffffff}) {check(before,after,i);count++;}
    System.out.println("MATCH "+count+" selectors");
  }
  static void check(Method before,Method after,int i) throws Exception {
    long a=Double.doubleToRawLongBits((Double)before.invoke(null,i,null,null));
    long b=Double.doubleToRawLongBits((Double)after.invoke(null,i,null,null));
    if(a!=b)throw new AssertionError("Mismatch at "+i+": "+a+" != "+b);
  }
}
