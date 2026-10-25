(module
  (type $f (func (result i32)))
  (table (export "tab") 1 funcref)
  (func $answer (type $f) i32.const 42)
  (elem (i32.const 0) func $answer))
