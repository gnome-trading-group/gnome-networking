#ifdef _LP64
  #ifndef jlong_to_ptr
    #define jlong_to_ptr(a) ((void*)(a))
  #endif
  #ifndef ptr_to_jlong
    #define ptr_to_jlong(a) ((jlong)(a))
  #endif
#else
  #ifndef jlong_to_ptr
    #define jlong_to_ptr(a) ((void*)(int)(a))
  #endif
  #ifndef ptr_to_jlong
    #define ptr_to_jlong(a) ((jlong)(int)(a))
  #endif
#endif