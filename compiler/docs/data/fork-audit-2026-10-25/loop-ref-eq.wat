(module
  (type $s (struct))
  (func (export "loop_ref_eq") (result i32)
    (local $iteration i32)
    (block $done (result i32)
      struct.new_default $s
      (loop $again (param eqref)
        i32.const 1234
        ref.i31
        ref.eq
        if
          i32.const 1
          br $done
        end
        local.get $iteration
        if
          i32.const 0
          br $done
        end
        i32.const 1
        local.set $iteration
        i32.const 1234
        ref.i31
        br $again)
      unreachable)))
