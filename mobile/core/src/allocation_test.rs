use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
thread_local! {static COUNT:Cell<Option<[usize;2]>>=const{Cell::new(None)};}
struct Alloc;
fn count(free: bool) {
    let _ = COUNT.try_with(|c| {
        if let Some(mut n) = c.get() {
            n[free as usize] += 1;
            c.set(Some(n));
        }
    });
}
unsafe impl GlobalAlloc for Alloc {
    unsafe fn alloc(&self, l: Layout) -> *mut u8 {
        count(false);
        unsafe { System.alloc(l) }
    }
    unsafe fn alloc_zeroed(&self, l: Layout) -> *mut u8 {
        count(false);
        unsafe { System.alloc_zeroed(l) }
    }
    unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
        count(true);
        unsafe { System.dealloc(p, l) }
    }
    unsafe fn realloc(&self, p: *mut u8, l: Layout, n: usize) -> *mut u8 {
        count(false);
        unsafe { System.realloc(p, l, n) }
    }
}
#[global_allocator]
static ALLOC: Alloc = Alloc;
pub fn probe(f: impl FnOnce()) -> serde_json::Value {
    struct Reset;
    impl Drop for Reset {
        fn drop(&mut self) {
            COUNT.with(|c| c.set(None));
        }
    }
    COUNT.with(|c| c.set(Some([0; 2])));
    let guard = Reset;
    f();
    let n = COUNT.with(|c| c.get().unwrap());
    drop(guard);
    serde_json::json!({"allocations_or_reallocations":n[0],"deallocations":n[1]})
}
