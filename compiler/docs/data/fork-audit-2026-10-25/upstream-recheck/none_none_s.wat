(module
  (type $s (struct))
  (func (export "i_none_s") (param i32) (result i32) (ref.eq (ref.null none) (struct.new $s)))
  (func $take (param eqref) (result i32) (ref.is_null (local.get 0)))
)