(module
  (type $s (struct))
  (func (export "i_nulls_typed") (param i32) (result i32) (ref.eq (ref.null $s) (ref.null none)))
  (func $take (param eqref) (result i32) (ref.is_null (local.get 0)))
)