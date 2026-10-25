(module
  (type $s (struct))
  (func (export "i_s_none") (param i32) (result i32) (ref.eq (struct.new $s) (ref.null none)))
  (func $take (param eqref) (result i32) (ref.is_null (local.get 0)))
)