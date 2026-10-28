(module
  ;; Each export reaches $compiled in a different way when it is the only interpreted function.
  (type $t (func (param i32) (result i32)))
  (func $compiled (type $t)
    local.get 0
    i32.const 1
    i32.add)
  (func $tail_called (type $t)
    local.get 0
    call $compiled)
  (func $tail_relay (type $t)
    local.get 0
    return_call $tail_called)
  (func (export "tail_call") (type $t)
    local.get 0
    return_call $tail_relay)
  (func (export "call_ref") (type $t)
    local.get 0
    ref.func $compiled
    call_ref $t)
  (func (export "tail_call_ref") (type $t)
    local.get 0
    ref.func $compiled
    return_call_ref $t)
  (elem declare func $compiled))
