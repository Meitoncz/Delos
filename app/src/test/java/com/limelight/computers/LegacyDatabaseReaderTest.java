package com.limelight.computers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.http.ComputerDetails;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class LegacyDatabaseReaderTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        ShadowLog.clear();
    }

    @Test
    public void missingFilesMigrateNothingAndLogNoException() {
        assertTrue(LegacyDatabaseReader.migrateAllComputers(context).isEmpty());
        assertTrue(LegacyDatabaseReader2.migrateAllComputers(context).isEmpty());
        assertTrue(LegacyDatabaseReader3.migrateAllComputers(context).isEmpty());

        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            assertEquals("No exception stack expected: " + item.msg, null, item.throwable);
        }
        assertFalse(context.getDatabasePath("computers.db").exists());
    }

    @Test
    public void existingFileIsMigratedAndDeleted() {
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("computers2.db"), null);
        db.execSQL("CREATE TABLE Computers(UUID TEXT PRIMARY KEY, ComputerName TEXT NOT NULL, LocalAddress TEXT NOT NULL,"
                + " RemoteAddress TEXT NOT NULL, ManualAddress TEXT NOT NULL, MacAddress TEXT)");
        db.execSQL("INSERT INTO Computers VALUES('uuid-1', 'Desk', '192.168.1.5', '203.0.113.9', '', 'AA:BB:CC:DD:EE:FF')");
        db.close();

        List<ComputerDetails> computers = LegacyDatabaseReader2.migrateAllComputers(context);

        assertEquals(1, computers.size());
        assertEquals("uuid-1", computers.get(0).uuid);
        assertEquals("Desk", computers.get(0).name);
        assertEquals("192.168.1.5", computers.get(0).localAddress.address);
        assertFalse(context.getDatabasePath("computers2.db").exists());
    }
}
