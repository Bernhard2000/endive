(module
  (type $s (struct))
  (func (export "i_none_none") (param i32) (result i32) (ref.eq (ref.null none) (ref.null none)))
  (func (export "i_s_none") (param i32) (result i32) (ref.eq (struct.new $s) (ref.null none)))
  (func (export "i_none_s") (param i32) (result i32) (ref.eq (ref.null none) (struct.new $s)))
  (func (export "i_nulls_typed") (param i32) (result i32) (ref.eq (ref.null $s) (ref.null none)))
  (func $take (param eqref) (result i32) (ref.is_null (local.get 0)))
  (func (export "i_call_none") (param i32) (result i32) (call $take (ref.null none)))
)
