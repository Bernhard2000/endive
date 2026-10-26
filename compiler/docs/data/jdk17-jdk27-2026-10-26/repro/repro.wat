;; Endive-level port of test1 from OpenJDK's TestFoldedIfsWrongReexec (JDK-8376400).
;; $testN traps (unreachable) iff helper says i >=u 16, then counts i <u C.
(module
  (global $taken (mut i32) (i32.const 0))
  (func (export "taken") (result i32) (global.get $taken))
  (func (export "reset") (global.set $taken (i32.const 0)))
  (func $helper (export "helper") (param $i i32) (param $j i32) (result i32)
    (local $k i32)
    (if (result i32) (i32.ge_u (local.get $i) (local.get $j))
      (then
        (block $b (loop $l
          (br_if $b (i32.ge_s (local.get $k) (i32.const 100)))
          (local.set $k (i32.add (local.get $k) (i32.const 1)))
          (br $l)))
        (i32.const 0))
      (else (i32.const 1))))
  (func $spin
    (local $j i32) (local $k i32)
    (block $bj (loop $lj
      (br_if $bj (i32.ge_s (local.get $j) (i32.const 10)))
      (local.set $k (i32.const 0))
      (block $bk (loop $lk
        (br_if $bk (i32.ge_s (local.get $k) (i32.const 10)))
        (local.set $k (i32.add (local.get $k) (i32.const 1)))
        (br $lk)))
      (local.set $j (i32.add (local.get $j) (i32.const 1)))
      (br $lj))))
  ;; constant 8: a power-of-two bound (the fork lowers lt_u 8 to a shift + zero test)
  (func (export "test1") (param $i i32)
    (if (i32.eqz (call $helper (local.get $i) (i32.const 16))) (then unreachable))
    (if (i32.lt_u (local.get $i) (i32.const 8))
      (then (global.set $taken (i32.add (global.get $taken) (i32.const 1)))))
    (call $spin))
  ;; constant 9: stays an unsigned compare helper call
  (func (export "test2") (param $i i32)
    (if (i32.eqz (call $helper (local.get $i) (i32.const 16))) (then unreachable))
    (if (i32.lt_u (local.get $i) (i32.const 9))
      (then (global.set $taken (i32.add (global.get $taken) (i32.const 1)))))
    (call $spin))
)
