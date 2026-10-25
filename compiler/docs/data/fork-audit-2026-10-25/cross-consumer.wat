(module
  (type $f (func (result i32)))
  (import "provider" "tab" (table 1 funcref))
  (func (export "ordinary") (result i32)
    i32.const 0 call_indirect (type $f))
  (func (export "tail") (result i32)
    i32.const 0 return_call_indirect (type $f)))
