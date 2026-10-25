(module
  (type $s (struct))
  (func (export "i_none_none") (param i32) (result i32) (ref.eq (ref.null none) (ref.null none)))
  (func $take (param eqref) (result i32) (ref.is_null (local.get 0)))
)