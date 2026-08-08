package android.net

/**
 * `android.net.Uri` is abstract with a package-private constructor, and the AGP unit-test stub
 * jar throws `RuntimeException("not mocked")` on every one of its methods unless
 * `unitTests.isReturnDefaultValues` is set - which in turn makes `Uri.parse(...)` return null,
 * and `DataSpec`'s constructor throws a `NullPointerException` on a null `Uri`. This fixture
 * lives in `android.net` purely to reach that package-private constructor from a test source
 * set. The only method `ChunkedDataSource` and `DataSpec` actually exercise on a `Uri` is
 * `toString()`, so that is the only one given real behaviour; anything else being called would
 * mean the test needs a different fixture, not a silently-wrong value, hence the throw.
 */
class TestUri(private val value: String) : Uri() {
    override fun toString(): String = value
    override fun buildUpon() = fail("buildUpon")
    override fun getAuthority(): String = fail("getAuthority")
    override fun getEncodedAuthority(): String = fail("getEncodedAuthority")
    override fun getEncodedFragment(): String = fail("getEncodedFragment")
    override fun getEncodedPath(): String = fail("getEncodedPath")
    override fun getEncodedQuery(): String = fail("getEncodedQuery")
    override fun getEncodedSchemeSpecificPart(): String = fail("getEncodedSchemeSpecificPart")
    override fun getEncodedUserInfo(): String = fail("getEncodedUserInfo")
    override fun getFragment(): String = fail("getFragment")
    override fun getHost(): String = fail("getHost")
    override fun getLastPathSegment(): String = fail("getLastPathSegment")
    override fun getPath(): String = fail("getPath")
    override fun getPathSegments(): List<String> = fail("getPathSegments")
    override fun getPort(): Int = fail("getPort")
    override fun getQuery(): String = fail("getQuery")
    override fun getScheme(): String = fail("getScheme")
    override fun getSchemeSpecificPart(): String = fail("getSchemeSpecificPart")
    override fun getUserInfo(): String = fail("getUserInfo")
    override fun isHierarchical(): Boolean = fail("isHierarchical")
    override fun isRelative(): Boolean = fail("isRelative")
    override fun describeContents(): Int = fail("describeContents")
    override fun writeToParcel(dest: android.os.Parcel, flags: Int): Unit = fail("writeToParcel")

    private fun fail(method: String): Nothing =
        throw UnsupportedOperationException("$method not needed by ChunkedDataSource tests")
}
