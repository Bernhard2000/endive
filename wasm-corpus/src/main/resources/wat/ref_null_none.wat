;; (ref null none) is the bottom of the any hierarchy: it is an eqref, structref, arrayref and
;; i31ref as well as an anyref. Producers such as Binaryen type every such null this way.
(module
  (type $s (struct))
  (func $is_null_eq (param eqref) (result i32) (ref.is_null (local.get 0)))
  (func $is_null_struct (param structref) (result i32) (ref.is_null (local.get 0)))
  (func $is_null_array (param arrayref) (result i32) (ref.is_null (local.get 0)))
  (func $is_null_i31 (param i31ref) (result i32) (ref.is_null (local.get 0)))
  (func (export "none_eq_none") (result i32) (ref.eq (ref.null none) (ref.null none)))
  (func (export "struct_eq_none") (result i32) (ref.eq (struct.new $s) (ref.null none)))
  (func (export "typed_null_eq_none") (result i32) (ref.eq (ref.null $s) (ref.null none)))
  (func (export "pass_none") (result i32)
    (i32.add
      (i32.add (call $is_null_eq (ref.null none)) (call $is_null_struct (ref.null none)))
      (i32.add (call $is_null_array (ref.null none)) (call $is_null_i31 (ref.null none)))))
)
