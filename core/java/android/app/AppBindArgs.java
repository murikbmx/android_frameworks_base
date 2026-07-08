package android.app;

/** @hide */
public interface AppBindArgs {
    String KEY_GOS_PACKAGE_STATE = "gosPs";
    String KEY_FLAGS_ARRAY = "flagsArr";

    int FLAGS_IDX_SPECIAL_RUNTIME_PERMISSIONS = 0;
    // 1: HOOKED_LOCATION_MANAGER, 2: DYN_CODE_LOADING (not ported)
    int FLAGS_IDX_GSERVICES_FLAGS_REDIRECT = 3;

    int FLAGS_ARRAY_LEN = 10;
}
