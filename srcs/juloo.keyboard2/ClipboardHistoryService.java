package juloo.keyboard2;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build.VERSION;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class ClipboardHistoryService
{
  /** Start the service on startup and start listening to clipboard changes. */
  public static void on_startup(Context ctx, ClipboardPasteCallback cb)
  {
    get_service(ctx);
    _paste_callback = cb;
  }

  /** Start the service if it hasn't been started before. Returns [null] if the
      feature is unsupported. */
  public static ClipboardHistoryService get_service(Context ctx)
  {
    if (VERSION.SDK_INT <= 11)
      return null;
    if (_service == null)
      _service = new ClipboardHistoryService(ctx);
    return _service;
  }

  public static void set_history_enabled(boolean e)
  {
    Config.globalConfig().set_clipboard_history_enabled(e);
    if (_service == null)
      return;
    if (e)
      _service.add_current_clip();
    else
      _service.clear_history();
  }

  /** Send the given string to the editor. */
  public static void paste(String clip)
  {
    if (_paste_callback != null)
      _paste_callback.paste_from_clipboard_pane(clip);
  }

  /** The maximum size limits the amount of user data stored in memory but also
      gives a sense to the user that the history is not persisted and can be
      forgotten as soon as the app stops. 
      Now configurable - 0 means unlimited. */
  /** Time in ms until history entries expire. */
  public static final long HISTORY_TTL_MS = 5 * 60 * 1000;

  static ClipboardHistoryService _service = null;
  static ClipboardPasteCallback _paste_callback = null;

  ClipboardManager _cm;
  ClipboardDatabase _database;
  OnClipboardHistoryChange _listener = null;

  ClipboardHistoryService(Context ctx)
  {
    _database = ClipboardDatabase.getInstance(ctx);
    _cm = (ClipboardManager)ctx.getSystemService(Context.CLIPBOARD_SERVICE);
    _cm.addPrimaryClipChangedListener(this.new SystemListener());
    
    // Clean up expired entries on startup
    _database.cleanupExpiredEntries();
  }

  public List<String> clear_expired_and_get_history()
  {
    // Clean up expired entries and return active ones
    _database.cleanupExpiredEntries();
    return _database.getActiveClipboardEntries();
  }

  /** This will call [on_clipboard_history_change]. */
  public void remove_history_entry(String clip)
  {
    // Check if this is the most recent clipboard entry
    List<String> currentHistory = _database.getActiveClipboardEntries();
    boolean isCurrentClip = !currentHistory.isEmpty() && currentHistory.get(0).equals(clip);
    
    // If removing the current clipboard, clear the system clipboard
    if (isCurrentClip)
    {
      if (VERSION.SDK_INT >= 28)
        _cm.clearPrimaryClip();
      else
        _cm.setText("");
    }
    
    // Remove from database
    boolean removed = _database.removeClipboardEntry(clip);
    if (removed && _listener != null)
      _listener.on_clipboard_history_change();
  }

  /** Add clipboard entries to the history, skipping consecutive duplicates and
      empty strings. */
  public void add_clip(String clip)
  {
    if (!Config.globalConfig().clipboard_history_enabled)
      return;
    
    if (clip == null || clip.trim().isEmpty())
      return;
    
    // Calculate expiry time
    long expiryTime = System.currentTimeMillis() + HISTORY_TTL_MS;
    
    // Add to database (handles duplicate detection automatically)
    boolean added = _database.addClipboardEntry(clip, expiryTime);
    
    if (added)
    {
      // Apply size limits if configured
      int maxHistorySize = Config.globalConfig().clipboard_history_limit;
      if (maxHistorySize > 0)
      {
        _database.applySizeLimit(maxHistorySize);
      }
      
      if (_listener != null)
        _listener.on_clipboard_history_change();
    }
  }

  public void clear_history()
  {
    _database.clearAllEntries();
    if (_listener != null)
      _listener.on_clipboard_history_change();
  }

  public void set_on_clipboard_history_change(OnClipboardHistoryChange l) { _listener = l; }
  
  /** Pin or unpin a clipboard entry to prevent it from expiring */
  public void set_pinned_status(String clip, boolean isPinned)
  {
    boolean updated = _database.setPinnedStatus(clip, isPinned);
    if (updated && _listener != null)
      _listener.on_clipboard_history_change();
  }
  
  /** Get statistics about clipboard storage */
  public String getStorageStats()
  {
    int total = _database.getTotalEntryCount();
    int active = _database.getActiveEntryCount();
    return String.format("Clipboard: %d active entries (%d total in database)", active, total);
  }

  public static interface OnClipboardHistoryChange
  {
    public void on_clipboard_history_change();
  }

  /** Add what is currently in the system clipboard into the history. */
  void add_current_clip()
  {
    ClipData clip = _cm.getPrimaryClip();
    if (clip == null)
      return;
    int count = clip.getItemCount();
    for (int i = 0; i < count; i++)
    {
      CharSequence text = clip.getItemAt(i).getText();
      if (text != null)
        add_clip(text.toString());
    }
  }

  final class SystemListener implements ClipboardManager.OnPrimaryClipChangedListener
  {
    public SystemListener() {}

    @Override
    public void onPrimaryClipChanged()
    {
      add_current_clip();
    }
  }

  // HistoryEntry class removed - now using SQLite database storage

  public interface ClipboardPasteCallback
  {
    public void paste_from_clipboard_pane(String content);
  }
}
