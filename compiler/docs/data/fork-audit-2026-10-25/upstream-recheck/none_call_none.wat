(module
  (type $s (struct))
  (func $take (param eqref) (result i32) (ref.is_null (local.get 0)))
  (func (export "i_call_none") (param i32) (result i32) (call $take (ref.null none)))
)