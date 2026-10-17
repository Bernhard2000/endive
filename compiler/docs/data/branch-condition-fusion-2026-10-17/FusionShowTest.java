package run.endive.compiler.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;
import run.endive.wasm.Parser;

public class FusionShowTest {
    static final String ROOT = "/home/bernhard/Dokumente/MasterThesis/Endive/fork/endive/wasm-corpus/src/main/resources/compiled/";
    static final String[][] SHOW = {
        {"branch_conditions_const.wat.wasm", "i32_lt_s_c0_br_if", "i32_lt_s_c1_br_if", "i32_lt_u_c256_br_if", "i32_gt_u_c255_if_eqz", "i32_lt_u_cmin_br_if", "i32_gt_u_c0_select", "i32_lt_u_c7_br_if"},
        {"branch_conditions.wat.wasm", "i64_lt_u_br_if", "i64_extend_u_eqz_br_if"},
        {"ref_conditions.wat.wasm", "struct_is_null_if", "func_is_null_br_if", "struct_br_on_null", "struct_br_on_non_null", "any_br_on_cast_fail"},
    };

    @Test
    public void show() throws Exception {
        var sb = new StringBuilder();
        for (var row : SHOW) {
            var module = Parser.parse(Files.readAllBytes(Path.of(ROOT + row[0])));
            var exports = new HashMap<String, Integer>();
            for (int i = 0; i < module.exportSection().exportCount(); i++) {
                exports.put(module.exportSection().getExport(i).name(), module.exportSection().getExport(i).index());
            }
            var result = Compiler.builder(module).build().compile();
            for (int r = 1; r < row.length; r++) {
                var method = "func_" + exports.get(row[r]);
                for (var bytes : result.classBytes().values()) {
                    var node = new ClassNode();
                    new ClassReader(bytes).accept(node, 0);
                    for (var m : node.methods) {
                        if (m.name.equals(method)) {
                            var t = new Textifier();
                            m.accept(new TraceMethodVisitor(t));
                            var sw = new StringWriter();
                            t.print(new PrintWriter(sw));
                            sb.append("### ").append(row[r]).append("\n").append(sw).append("\n");
                        }
                    }
                }
            }
        }
        Files.writeString(Path.of(System.getProperty("fusion.out")), sb.toString());
    }
}
