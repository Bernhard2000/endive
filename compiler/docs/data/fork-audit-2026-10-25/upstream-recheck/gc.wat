(module
  (type $s (struct (field i32)))
  (type $t (struct (field i64)))
  (global $g (mut eqref) (ref.i31 (i32.const 5)))
  (func $pick (param i32) (result eqref)
    (if (result eqref) (local.get 0) (then (ref.i31 (i32.const 5))) (else (struct.new $s (i32.const 1)))))
  ;; block result via br carries i31, fallthrough carries struct
  (func (export "i_block_br") (param i32) (result i32)
    (if (result i32)
      (ref.eq
        (block $b (result eqref)
          (br_if $b (ref.i31 (i32.const 5)) (local.get 0))
          (drop)
          (struct.new $s (i32.const 1)))
        (ref.i31 (i32.const 5)))
      (then (i32.const 1)) (else (i32.const 0))))
  ;; if/else merge
  (func (export "i_if_merge") (param i32) (result i32)
    (select (i32.const 1) (i32.const 0)
      (ref.eq (call $pick (local.get 0)) (ref.i31 (i32.const 5)))))
  ;; typed select with struct first operand
  (func (export "i_select_t") (param i32) (result i32)
    (if (result i32)
      (ref.eq
        (select (result eqref) (struct.new $s (i32.const 1)) (ref.i31 (i32.const 5)) (i32.eqz (local.get 0)))
        (ref.i31 (i32.const 5)))
      (then (i32.const 1)) (else (i32.const 0))))
  ;; br_table carrying refs to two blocks
  (func (export "i_br_table") (param i32) (result i32)
    (if (result i32)
      (ref.eq
        (block $a (result eqref)
          (block $b (result eqref)
            (br_table $a $b (ref.i31 (i32.const 5)) (local.get 0))))
        (ref.i31 (i32.const 5)))
      (then (i32.const 1)) (else (i32.const 0))))
  ;; br_on_cast_fail: fallthrough is (ref $s), branch carries the rest
  (func (export "i_br_on_cast_fail") (param i32) (result i32)
    (if (result i32)
      (ref.eq
        (block $out (result eqref)
          (br_on_cast_fail $out eqref (ref $s) (call $pick (local.get 0)))
          )
        (ref.i31 (i32.const 5)))
      (then (i32.const 1)) (else (i32.const 0))))
  ;; global and local tee
  (func (export "i_global") (param i32) (result i32)
    (local $l eqref)
    (if (local.get 0) (then (global.set $g (struct.new $s (i32.const 1)))))
    (ref.eq (local.tee $l (global.get $g)) (ref.i31 (i32.const 5))))
  ;; nulls
  (func (export "i_nulls") (param i32) (result i32)
    (if (result i32)
      (ref.eq
        (if (result (ref null $s)) (local.get 0) (then (ref.null $s)) (else (struct.new $s (i32.const 3))))
        (ref.null $s))
      (then (i32.const 1)) (else (i32.const 0))))
  ;; br_on_null / br_on_non_null with a struct
  (func (export "i_br_on_null") (param i32) (result i32)
    (block $n
      (drop (br_on_null $n
        (if (result (ref null $s)) (local.get 0) (then (ref.null $s)) (else (struct.new $s (i32.const 3))))))
      (return (i32.const 2)))
    (i32.const 3))
  (func (export "i_br_on_non_null") (param i32) (result i32)
    (block $nn (result (ref $s))
      (br_on_non_null $nn
        (if (result (ref null $s)) (local.get 0) (then (ref.null $s)) (else (struct.new $s (i32.const 3)))))
      (return (i32.const 4)))
    (struct.get $s 0))
  ;; br_on_cast to a block, then compare with i31
  (func (export "i_br_on_cast") (param i32) (result i32)
    (block $hit (result (ref i31))
      (br_on_cast $hit eqref (ref i31) (call $pick (local.get 0)))
      (return (i32.const 8)))
    (i31.get_s))
  ;; ref.is_null on eqref via function result
  (func (export "i_is_null") (param i32) (result i32)
    (if (result i32) (ref.is_null (if (result eqref) (local.get 0) (then (ref.null eq)) (else (call $pick (i32.const 1)))))
      (then (i32.const 1)) (else (i32.const 0))))
  ;; loop param (F01 control)
  (func (export "i_loop") (param i32) (result i32)
    (local $n i32) (local $hits i32)
    (struct.new $s (i32.const 1))
    (loop $l (param eqref)
      (if (ref.eq (ref.i31 (i32.const 5))) (then (local.set $hits (i32.add (local.get $hits) (i32.const 1)))))
      (local.set $n (i32.add (local.get $n) (i32.const 1)))
      (drop (br_if $l (ref.i31 (i32.const 5)) (i32.lt_u (local.get $n) (i32.const 2)))))
    (local.get $hits))
)
