package juloo.keyboard2;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * Robust SQLite-based storage for clipboard history.
 * Provides persistent storage that survives app restarts and prevents data loss.
 */
public class ClipboardDatabase extends SQLiteOpenHelper
{
    private static final String DATABASE_NAME = "clipboard_history.db";
    private static final int DATABASE_VERSION = 1;
    
    // Table and column names
    private static final String TABLE_CLIPBOARD = "clipboard_entries";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_CONTENT = "content";
    private static final String COLUMN_TIMESTAMP = "timestamp";
    private static final String COLUMN_EXPIRY_TIMESTAMP = "expiry_timestamp";
    private static final String COLUMN_IS_PINNED = "is_pinned";
    private static final String COLUMN_CONTENT_HASH = "content_hash";
    
    private static ClipboardDatabase _instance = null;
    
    public static ClipboardDatabase getInstance(Context context)
    {
        if (_instance == null)
        {
            synchronized (ClipboardDatabase.class)
            {
                if (_instance == null)
                {
                    _instance = new ClipboardDatabase(context.getApplicationContext());
                }
            }
        }
        return _instance;
    }
    
    private ClipboardDatabase(Context context)
    {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }
    
    @Override
    public void onCreate(SQLiteDatabase db)
    {
        String createTable = "CREATE TABLE " + TABLE_CLIPBOARD + " (" +
            COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COLUMN_CONTENT + " TEXT NOT NULL, " +
            COLUMN_TIMESTAMP + " INTEGER NOT NULL, " +
            COLUMN_EXPIRY_TIMESTAMP + " INTEGER NOT NULL, " +
            COLUMN_IS_PINNED + " INTEGER DEFAULT 0, " +
            COLUMN_CONTENT_HASH + " TEXT NOT NULL" +
            ")";
        
        db.execSQL(createTable);
        
        // Create index on content hash for fast duplicate detection
        db.execSQL("CREATE INDEX idx_content_hash ON " + TABLE_CLIPBOARD + " (" + COLUMN_CONTENT_HASH + ")");
        
        // Create index on timestamp for efficient cleanup and ordering
        db.execSQL("CREATE INDEX idx_timestamp ON " + TABLE_CLIPBOARD + " (" + COLUMN_TIMESTAMP + " DESC)");
        
        // Create index on expiry timestamp for efficient cleanup
        db.execSQL("CREATE INDEX idx_expiry ON " + TABLE_CLIPBOARD + " (" + COLUMN_EXPIRY_TIMESTAMP + ")");
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion)
    {
        // For now, just recreate the table
        // In future versions, implement proper migration
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CLIPBOARD);
        onCreate(db);
    }
    
    /**
     * Add a new clipboard entry to the database
     */
    public boolean addClipboardEntry(String content, long expiryTimestamp)
    {
        if (content == null || content.trim().isEmpty())
            return false;
            
        content = content.trim();
        String contentHash = String.valueOf(content.hashCode());
        
        SQLiteDatabase db = this.getWritableDatabase();
        
        try
        {
            // Check for duplicate content (ignore expired entries)
            long currentTime = System.currentTimeMillis();
            String duplicateQuery = "SELECT " + COLUMN_ID + " FROM " + TABLE_CLIPBOARD + 
                " WHERE " + COLUMN_CONTENT_HASH + " = ? AND " + COLUMN_CONTENT + " = ? AND " +
                COLUMN_EXPIRY_TIMESTAMP + " > ?";
                
            Cursor cursor = db.rawQuery(duplicateQuery, new String[]{contentHash, content, String.valueOf(currentTime)});
            
            if (cursor.getCount() > 0)
            {
                cursor.close();
                android.util.Log.d("ClipboardDatabase", "Duplicate entry ignored: " + content.substring(0, Math.min(content.length(), 20)) + "...");
                return false; // Duplicate content
            }
            cursor.close();
            
            // Insert new entry
            ContentValues values = new ContentValues();
            values.put(COLUMN_CONTENT, content);
            values.put(COLUMN_TIMESTAMP, System.currentTimeMillis());
            values.put(COLUMN_EXPIRY_TIMESTAMP, expiryTimestamp);
            values.put(COLUMN_IS_PINNED, 0);
            values.put(COLUMN_CONTENT_HASH, contentHash);
            
            long result = db.insert(TABLE_CLIPBOARD, null, values);
            
            android.util.Log.d("ClipboardDatabase", "Added clipboard entry: " + content.substring(0, Math.min(content.length(), 20)) + "... (id=" + result + ")");
            return result != -1;
        }
        catch (Exception e)
        {
            android.util.Log.e("ClipboardDatabase", "Error adding clipboard entry: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get all active clipboard entries (non-expired)
     */
    public List<String> getActiveClipboardEntries()
    {
        List<String> entries = new ArrayList<>();
        long currentTime = System.currentTimeMillis();
        
        SQLiteDatabase db = this.getReadableDatabase();
        
        String query = "SELECT " + COLUMN_CONTENT + " FROM " + TABLE_CLIPBOARD + 
            " WHERE " + COLUMN_EXPIRY_TIMESTAMP + " > ? OR " + COLUMN_IS_PINNED + " = 1" +
            " ORDER BY " + COLUMN_IS_PINNED + " DESC, " + COLUMN_TIMESTAMP + " DESC";
            
        Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(currentTime)});
        
        try
        {
            if (cursor.moveToFirst())
            {
                do
                {
                    String content = cursor.getString(0);
                    entries.add(content);
                } while (cursor.moveToNext());
            }
        }
        catch (Exception e)
        {
            android.util.Log.e("ClipboardDatabase", "Error retrieving clipboard entries: " + e.getMessage());
        }
        finally
        {
            cursor.close();
        }
        
        android.util.Log.d("ClipboardDatabase", "Retrieved " + entries.size() + " active clipboard entries");
        return entries;
    }
    
    /**
     * Remove a specific clipboard entry
     */
    public boolean removeClipboardEntry(String content)
    {
        if (content == null || content.trim().isEmpty())
            return false;
            
        content = content.trim();
        SQLiteDatabase db = this.getWritableDatabase();
        
        try
        {
            int deletedRows = db.delete(TABLE_CLIPBOARD, 
                COLUMN_CONTENT + " = ?", 
                new String[]{content});
                
            android.util.Log.d("ClipboardDatabase", "Removed " + deletedRows + " clipboard entries matching: " + content.substring(0, Math.min(content.length(), 20)) + "...");
            return deletedRows > 0;
        }
        catch (Exception e)
        {
            android.util.Log.e("ClipboardDatabase", "Error removing clipboard entry: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Clear all clipboard entries (except pinned ones)
     */
    public void clearAllEntries()
    {
        SQLiteDatabase db = this.getWritableDatabase();
        
        try
        {
            int deletedRows = db.delete(TABLE_CLIPBOARD, 
                COLUMN_IS_PINNED + " = 0", 
                null);
                
            android.util.Log.d("ClipboardDatabase", "Cleared " + deletedRows + " clipboard entries (kept pinned entries)");
        }
        catch (Exception e)
        {
            android.util.Log.e("ClipboardDatabase", "Error clearing clipboard entries: " + e.getMessage());
        }
    }
    
    /**
     * Clean up expired entries to maintain database size
     */
    public int cleanupExpiredEntries()
    {
        long currentTime = System.currentTimeMillis();
        SQLiteDatabase db = this.getWritableDatabase();
        
        try
        {
            // Delete expired entries (except pinned ones)
            int deletedRows = db.delete(TABLE_CLIPBOARD, 
                COLUMN_EXPIRY_TIMESTAMP + " <= ? AND " + COLUMN_IS_PINNED + " = 0", 
                new String[]{String.valueOf(currentTime)});
                
            if (deletedRows > 0)
            {
                android.util.Log.d("ClipboardDatabase", "Cleaned up " + deletedRows + " expired clipboard entries");
            }
            
            return deletedRows;
        }
        catch (Exception e)
        {
            android.util.Log.e("ClipboardDatabase", "Error cleaning up expired entries: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Pin/unpin a clipboard entry to prevent expiration
     */
    public boolean setPinnedStatus(String content, boolean isPinned)
    {
        if (content == null || content.trim().isEmpty())
            return false;
            
        content = content.trim();
        SQLiteDatabase db = this.getWritableDatabase();
        
        try
        {
            ContentValues values = new ContentValues();
            values.put(COLUMN_IS_PINNED, isPinned ? 1 : 0);
            
            int updatedRows = db.update(TABLE_CLIPBOARD, values, 
                COLUMN_CONTENT + " = ?", 
                new String[]{content});
                
            android.util.Log.d("ClipboardDatabase", "Updated pin status for " + updatedRows + " entries: " + content.substring(0, Math.min(content.length(), 20)) + "... (pinned=" + isPinned + ")");
            return updatedRows > 0;
        }
        catch (Exception e)
        {
            android.util.Log.e("ClipboardDatabase", "Error updating pin status: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get total number of entries in database
     */
    public int getTotalEntryCount()
    {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_CLIPBOARD, null);
        
        int count = 0;
        if (cursor.moveToFirst())
        {
            count = cursor.getInt(0);
        }
        cursor.close();
        
        return count;
    }
    
    /**
     * Get count of active (non-expired) entries
     */
    public int getActiveEntryCount()
    {
        long currentTime = System.currentTimeMillis();
        SQLiteDatabase db = this.getReadableDatabase();
        
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_CLIPBOARD + 
            " WHERE " + COLUMN_EXPIRY_TIMESTAMP + " > ? OR " + COLUMN_IS_PINNED + " = 1",
            new String[]{String.valueOf(currentTime)});
        
        int count = 0;
        if (cursor.moveToFirst())
        {
            count = cursor.getInt(0);
        }
        cursor.close();
        
        return count;
    }
    
    /**
     * Apply size limits by removing oldest entries (except pinned)
     */
    public int applySizeLimit(int maxSize)
    {
        if (maxSize <= 0)
            return 0; // No limit
            
        SQLiteDatabase db = this.getWritableDatabase();
        
        try
        {
            // Count current non-pinned entries
            long currentTime = System.currentTimeMillis();
            Cursor countCursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_CLIPBOARD + 
                " WHERE " + COLUMN_IS_PINNED + " = 0 AND " + COLUMN_EXPIRY_TIMESTAMP + " > ?",
                new String[]{String.valueOf(currentTime)});
                
            int currentCount = 0;
            if (countCursor.moveToFirst())
            {
                currentCount = countCursor.getInt(0);
            }
            countCursor.close();
            
            if (currentCount <= maxSize)
                return 0; // No cleanup needed
            
            // Delete oldest entries to stay within limit
            int entriesToDelete = currentCount - maxSize;
            
            String deleteQuery = "DELETE FROM " + TABLE_CLIPBOARD + 
                " WHERE " + COLUMN_ID + " IN (" +
                "   SELECT " + COLUMN_ID + " FROM " + TABLE_CLIPBOARD +
                "   WHERE " + COLUMN_IS_PINNED + " = 0 AND " + COLUMN_EXPIRY_TIMESTAMP + " > ?" +
                "   ORDER BY " + COLUMN_TIMESTAMP + " ASC" +
                "   LIMIT ?" +
                ")";
                
            db.execSQL(deleteQuery, new Object[]{currentTime, entriesToDelete});
            
            android.util.Log.d("ClipboardDatabase", "Applied size limit: removed " + entriesToDelete + " oldest entries (limit=" + maxSize + ")");
            return entriesToDelete;
        }
        catch (Exception e)
        {
            android.util.Log.e("ClipboardDatabase", "Error applying size limit: " + e.getMessage());
            return 0;
        }
    }
}