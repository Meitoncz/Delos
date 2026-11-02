package com.limelight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class PosterContentProviderTest {
    private static final byte[] POSTER = {1, 2, 3, 4};
    private static final byte[] SECRET = {9, 9, 9};

    private PosterContentProvider provider;
    private File cacheDir;
    private File outsideFile;

    @Before
    public void setUp() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        cacheDir = context.getCacheDir();
        File dir = new File(cacheDir, "boxart/pc-1");
        assertTrue(dir.mkdirs() || dir.isDirectory());
        Files.write(new File(dir, "42.png").toPath(), POSTER);
        // Owned by this test, sits next to the box art cache: cache/secret.png
        outsideFile = new File(cacheDir, "secret.png");
        Files.write(outsideFile.toPath(), SECRET);
        provider = Robolectric.buildContentProvider(PosterContentProvider.class)
                .create(PosterContentProvider.AUTHORITY).get();
    }

    @After
    public void tearDown() {
        outsideFile.delete();
        new File(cacheDir, "boxart/pc-1/42.png").delete();
        new File(cacheDir, "boxart/pc-1").delete();
    }

    private static void assertTrue(boolean b) {
        org.junit.Assert.assertTrue(b);
    }

    private byte[] read(String path) throws IOException {
        Uri uri = Uri.parse("content://" + PosterContentProvider.AUTHORITY + path);
        try (ParcelFileDescriptor pfd = provider.openFile(uri, "r");
             FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            return in.readAllBytes();
        }
    }

    private void assertRejected(String path) {
        assertThrows(FileNotFoundException.class, () -> read(path));
    }

    @Test
    public void normalPosterUriReturnsCachedImage() throws IOException {
        assertArrayEquals(POSTER, read("/boxart/pc-1/42"));
        Uri built = PosterContentProvider.createBoxArtUri("pc-1", "42");
        try (ParcelFileDescriptor pfd = provider.openFile(built, "r");
             FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            assertArrayEquals(POSTER, in.readAllBytes());
        }
    }

    @Test
    public void traversalInUuidIsRejected() {
        assertRejected("/boxart/../42");
        assertRejected("/boxart/%2e%2e/42");
        assertRejected("/boxart/%2e%2e%2f/42");
        assertRejected("/boxart/..%2f/42");
        assertRejected("/boxart/%252e%252e/42");
        assertRejected("/boxart/%252e%252e%252f/42");
        assertRejected("/boxart/pc-1%2f..%2f..%2fsecret/42");
        assertRejected("/boxart/%2fetc/42");
        assertRejected("/boxart/pc-1%5c..%5c..%5csecret/42");
    }

    @Test
    public void traversalInAppIdIsRejected() {
        assertRejected("/boxart/pc-1/..%2f..%2fsecret");
        assertRejected("/boxart/pc-1/%2e%2e%2f%2e%2e%2fsecret");
        assertRejected("/boxart/pc-1/%252e%252e%252fsecret");
        assertRejected("/boxart/pc-1/..");
    }

    @Test
    public void traversalHelperNeverReturnsOutsideFile() {
        assertThrows(FileNotFoundException.class,
                () -> PosterContentProvider.resolveBoxArtFile(cacheDir, "..", "42"));
        assertThrows(FileNotFoundException.class,
                () -> PosterContentProvider.resolveBoxArtFile(cacheDir, "pc-1/../..", "42"));
        assertThrows(FileNotFoundException.class,
                () -> PosterContentProvider.resolveBoxArtFile(cacheDir, "pc-1", "../../secret"));
    }

    @Test
    public void invalidAppIdsAreControlledErrors() {
        assertRejected("/boxart/pc-1/abc");
        assertRejected("/boxart/pc-1/-1");
        assertRejected("/boxart/pc-1/+42");
        assertRejected("/boxart/pc-1/4.2");
        assertRejected("/boxart/pc-1/99999999999");
        assertRejected("/boxart/pc-1/2147483648");
        assertRejected("/boxart/pc-1/%20");
    }

    @Test
    public void missingImageIsControlledError() {
        assertRejected("/boxart/pc-1/43");
        assertRejected("/boxart/unknown-pc/42");
        assertRejected("/boxart/pc-1");
    }

    @Test
    public void symlinkEscapingTheCacheIsRejected() throws IOException {
        File link = new File(cacheDir, "boxart/pc-1/7.png");
        try {
            Files.createSymbolicLink(link.toPath(), outsideFile.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.Assume.assumeNoException(e);
        }
        try {
            assertRejected("/boxart/pc-1/7");
        } finally {
            link.delete();
        }
    }
}
