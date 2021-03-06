package org.bitseal.activities;

import info.guardianproject.cacheword.CacheWordHandler;
import info.guardianproject.cacheword.ICacheWordSubscriber;

import java.util.ArrayList;

import org.bitseal.R;
import org.bitseal.data.Address;
import org.bitseal.data.AddressBookRecord;
import org.bitseal.data.Message;
import org.bitseal.database.AddressBookRecordProvider;
import org.bitseal.database.AddressBookRecordsTable;
import org.bitseal.database.AddressProvider;
import org.bitseal.database.AddressesTable;
import org.bitseal.database.MessageProvider;
import org.bitseal.database.MessagesTable;
import org.bitseal.services.AppLockHandler;
import org.bitseal.services.NotificationsService;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The Activity class for the display of a single message. 
 * 
 * @author Jonathan Coe
 */
public class InboxMessageActivity extends Activity implements ICacheWordSubscriber
{	
	public static final String EXTRA_MESSAGE_ID = "inboxMessageActivity.MESSAGE_ID";
	public static final String EXTRA_SENDER_ADDRESS = "inboxMessageActivity.SENDER_ADDRESS";
	
	public static final String EXTRA_COLOUR_R = "inboxMessageActivity.COLOUR_R";
	public static final String EXTRA_COLOUR_G = "inboxMessageActivity.COLOUR_G";
	public static final String EXTRA_COLOUR_B = "inboxMessageActivity.COLOUR_B";
	
	public static final String FLAG_INBOX_MESSAGE_DELETED = "inboxMessageDeleted";
	
	private ArrayList<Message> mMessages;
	
	private Message mMessage;
	private long mMessageId;
	
	private View mMainView;
	
	private TextView mToAddressTextView;
	private TextView mFromAddressTextView;
	private TextView mSubjectTextView;
	private TextView mBodyTextView;
	
	private Button mReplyButton;
	private Button mCopyButton;
	private Button mDeleteButton;
	
	private int mColourR;
	private int mColourG;
	private int mColourB;
	
	private boolean mSenderInAddressBook;
	
	private static final int INBOX_MESSAGE_COLOURS_ALPHA_VALUE = 70;
	
    /** The key for a boolean variable that records whether or not a user-defined database encryption passphrase has been saved */
    private static final String KEY_DATABASE_PASSPHRASE_SAVED = "databasePassphraseSaved"; 
    
    private CacheWordHandler mCacheWordHandler;
	
    private static final String TAG = "INBOX_MESSAGE_ACTIVITY";
	
	@Override
	protected void onCreate(Bundle savedInstanceState) 
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_inbox_message);
		
        // Check whether the user has set a database encryption passphrase
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		if (prefs.getBoolean(KEY_DATABASE_PASSPHRASE_SAVED, false))
		{
			// Connect to the CacheWordService
			mCacheWordHandler = new CacheWordHandler(this);
			mCacheWordHandler.connectToService();
		}
		
		// Inflate the layout objects for this activity
		mToAddressTextView = (TextView) findViewById(R.id.inbox_message_toAddress_textview);
		mFromAddressTextView = (TextView) findViewById(R.id.inbox_message_fromAddress_textview);
		mSubjectTextView = (TextView) findViewById(R.id.inbox_message_subject_textview);
		mBodyTextView = (TextView) findViewById(R.id.inbox_message_body_textview);
		
		Bundle b = getIntent().getExtras();
		mMessageId = b.getLong(EXTRA_MESSAGE_ID);
		mColourR = b.getInt(EXTRA_COLOUR_R);
		mColourG = b.getInt(EXTRA_COLOUR_G);
		mColourB = b.getInt(EXTRA_COLOUR_B);
		
		// Retrieve the Message object from the database
		MessageProvider msgProv = MessageProvider.get(getApplicationContext());
		ArrayList<Message> retrievedMessages = msgProv.searchMessages(MessagesTable.COLUMN_ID, String.valueOf(mMessageId));
		mMessage = retrievedMessages.get(0);
		
		// If this is the first time that this message has been opened, set the 'read' field
		// of this Message to true and update the database to record that
		if (mMessage.hasBeenRead() == false)
		{
			mMessage.setRead(true);
			msgProv.updateMessage(mMessage);
			
			// If the user is opening unread messages from the inbox then we can clear any 'new messages' notifications
			NotificationManager notificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
			notificationManager.cancel(NotificationsService.getNewMessagesNotificationId());
			// Set the 'new messages notification currently displayed' shared preference to false
			SharedPreferences.Editor editor = prefs.edit();
		    editor.putBoolean(NotificationsService.KEY_NEW_MESSAGES_NOTIFICATION_CURRENTLY_DISPLAYED, false);
		    editor.commit();
		    Log.i(TAG, "Recorded dismissal of new messages notification");
		}
		
		// Set the display data for this activity with the data from the Message
        String toAddressString = mMessage.getToAddress();
        // Check if we can find the proper label for this address. If we can, then display it rather than the raw address.
        AddressProvider addProv = AddressProvider.get(getApplicationContext());
        ArrayList<Address> retrievedAddresses = addProv.searchAddresses(AddressesTable.COLUMN_ADDRESS, toAddressString);
		if (retrievedAddresses.size() > 0)
		{
			mToAddressTextView.setText(retrievedAddresses.get(0).getLabel());
			mToAddressTextView.setTextSize(15);
		}
		else if (toAddressString.equals(getResources().getString(R.string.inbox_welcome_message_to_address))) 
		{
			// If this is the welcome message, we want the "to" label to be full size, even though it isn't really a valid label
			mToAddressTextView.setText(toAddressString);
			mToAddressTextView.setTextSize(15);
		}
		else
		{
			mToAddressTextView.setText(toAddressString);
		}
		
        if (mMessage.getSubject() == null)
        {
        	mSubjectTextView.setText("[No subject]");
        }
        else
        {
        	mSubjectTextView.setText(mMessage.getSubject());
        }
		mBodyTextView.setText(mMessage.getBody());
		
		mReplyButton = (Button) findViewById(R.id.inbox_message_reply_button);	
		mReplyButton.setOnClickListener(new View.OnClickListener()
		{
			@Override
			public void onClick(View v) 
			{
				Log.i(TAG, "Inbox message reply button pressed");
				
				Intent i = new Intent(getBaseContext(), ComposeActivity.class);
		        i.putExtra(ComposeActivity.EXTRA_TO_ADDRESS, mMessage.getFromAddress());
		        i.putExtra(ComposeActivity.EXTRA_FROM_ADDRESS, mMessage.getToAddress());
		        i.putExtra(ComposeActivity.EXTRA_SUBJECT, mMessage.getSubject());
		        i.putExtra(ComposeActivity.EXTRA_COLOUR_R, mColourR);
		        i.putExtra(ComposeActivity.EXTRA_COLOUR_G, mColourG);
		        i.putExtra(ComposeActivity.EXTRA_COLOUR_B, mColourB);
		        startActivityForResult(i, 0);
			}
		});	
		
		mCopyButton = (Button) findViewById(R.id.inbox_message_copy_button);	
		mCopyButton.setOnClickListener(new View.OnClickListener()
		{
			@SuppressWarnings("deprecation")
			@TargetApi(Build.VERSION_CODES.HONEYCOMB)
			@Override
			public void onClick(View v) 
			{
				Log.i(TAG, "Inbox message copy button pressed");
				
				int sdk = android.os.Build.VERSION.SDK_INT;
				
				if(sdk < android.os.Build.VERSION_CODES.HONEYCOMB) 
				{
				    android.text.ClipboardManager clipboard = (android.text.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
				    clipboard.setText(mMessage.getBody());
				}
				
				else 
				{
				    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE); 
				    android.content.ClipData clip = android.content.ClipData.newPlainText("COPIED_MESSAGE_TEXT", mMessage.getBody());
				    clipboard.setPrimaryClip(clip);
				}
							
				Toast.makeText(getApplicationContext(), R.string.inbox_message_message_copied, Toast.LENGTH_LONG).show();
			}
		});	
		
		mDeleteButton = (Button) findViewById(R.id.inbox_message_delete_button);
		mDeleteButton.setOnClickListener(new View.OnClickListener()
		{
			@Override
			public void onClick(View v) 
			{
				Log.i(TAG, "Inbox message delete button pressed");
		        
		        // Open a dialog to confirm or cancel the deletion of the message
				final Dialog deleteDialog = new Dialog(InboxMessageActivity.this);
				LinearLayout dialogLayout = (LinearLayout) View.inflate(InboxMessageActivity.this, R.layout.dialog_inbox_message_delete, null);
				deleteDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
				deleteDialog.setContentView(dialogLayout);
				
				WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
			    lp.copyFrom(deleteDialog.getWindow().getAttributes());
			    lp.width = WindowManager.LayoutParams.MATCH_PARENT;
				
			    deleteDialog.show();
			    deleteDialog.getWindow().setAttributes(lp);		  
			    
			    Button confirmButton = (Button) dialogLayout.findViewById(R.id.inbox_message_delete_dialog_confirm_button);
			    confirmButton.setOnClickListener(new View.OnClickListener()
				{
					@Override
					public void onClick(View v)
					{
						Log.i(TAG, "Inbox message delete dialog confirm button pressed");							
						
						MessageProvider msgProv = MessageProvider.get(getApplicationContext());	
						mMessages = msgProv.getAllMessages();
						mMessages.remove(mMessage);
						msgProv.deleteMessage(mMessage);
						
						// Set a flag so that the 'Inbox' Activity can adjust its list view properly
						SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
						SharedPreferences.Editor editor = prefs.edit();
					    editor.putBoolean(FLAG_INBOX_MESSAGE_DELETED, true);
					    editor.commit();
				        
				        deleteDialog.dismiss();
				        
				        Toast.makeText(getApplicationContext(), R.string.inbox_message_message_deleted, Toast.LENGTH_SHORT).show();
				        					
						Intent i = new Intent(getBaseContext(), InboxActivity.class);
				        startActivityForResult(i, 0);
					}
				});
			    
			    Button cancelButton = (Button) dialogLayout.findViewById(R.id.inbox_message_delete_dialog_cancel_button);
			    cancelButton.setOnClickListener(new View.OnClickListener()
				{
					@Override
					public void onClick(View v)
					{
						Log.i(TAG, "Inbox message delete dialog cancel button pressed");							
						
						deleteDialog.dismiss();
					}
				});
			}
		});
		
        // Check whether we have an entry for this address in our address book.
		String fromAddressString = mMessage.getFromAddress();
        AddressBookRecordProvider addBookProv = AddressBookRecordProvider.get(getApplicationContext());
        ArrayList<AddressBookRecord> retrievedAddressBookRecords = addBookProv.searchAddressBookRecords(AddressBookRecordsTable.COLUMN_ADDRESS, fromAddressString);
		if (retrievedAddressBookRecords.size() > 0)
		{
			 // Substitute the label of that entry for the address.
			mFromAddressTextView.setText(retrievedAddressBookRecords.get(0).getLabel());
			mFromAddressTextView.setTextSize(15);
			mSenderInAddressBook = true;
		}
		else
		{
			mFromAddressTextView.setText(fromAddressString);
		}
		
		// Set the colours inherited from the inbox list view
		int color = Color.argb(0, mColourR, mColourG, mColourB);
		mToAddressTextView.setBackgroundColor(color);
		mFromAddressTextView.setBackgroundColor(color);
		mSubjectTextView.setBackgroundColor(color);
		mBodyTextView.setBackgroundColor(color);
		
		int backgroundColor = Color.argb(INBOX_MESSAGE_COLOURS_ALPHA_VALUE, mColourR, mColourG, mColourB);
		mMainView = (View) findViewById(R.id.inbox_message_scrollView);
		mMainView.setBackgroundColor(backgroundColor);
	}
	
	@Override
	protected void onSaveInstanceState (Bundle outState) 
	{
	    super.onSaveInstanceState(outState);
	}	       
		        
  	@Override
  	public boolean onCreateOptionsMenu(Menu menu) 
  	{
	    // Inflate the menu items for use in the action bar
	    getMenuInflater().inflate(R.menu.inbox_message_activity_actions, menu);
	    
	    MenuItem addToAddressBookAction = menu.findItem(R.id.action_add_to_address_book);
	    if (mSenderInAddressBook)
	    {
	    	addToAddressBookAction.setVisible(false);
	    }
	    
	    return super.onCreateOptionsMenu(menu);
  	}
      
      @Override
      public boolean onPrepareOptionsMenu(Menu menu)
      {
      	SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
      	if (prefs.getBoolean(KEY_DATABASE_PASSPHRASE_SAVED, false) == false)
  		{
  			menu.removeItem(R.id.menu_item_lock);
  		}
          return super.onPrepareOptionsMenu(menu);
      }
  	
  	@SuppressLint("InlinedApi")
  	@Override
  	public boolean onOptionsItemSelected(MenuItem item) 
  	{
  	    switch(item.getItemId()) 
  	    {
	    	case R.id.action_add_to_address_book:
	    		Intent i = new Intent(getBaseContext(), AddressBookActivity.class);
		        i.putExtra(EXTRA_SENDER_ADDRESS, mMessage.getFromAddress());
		        startActivityForResult(i, 0);
		        break;
  	    
  	    	case R.id.menu_item_inbox:
  		        Intent intent1 = new Intent(this, InboxActivity.class);
  		        startActivity(intent1);
  		        break;
  		        
  		    case R.id.menu_item_sent:
  		        Intent intent2 = new Intent(this, SentActivity.class);
  		        startActivity(intent2);
  		        break;  
  		        
  		    case R.id.menu_item_compose:
  		        Intent intent3 = new Intent(this, ComposeActivity.class);
  		        startActivity(intent3);
  		        break;
  		        
  		    case R.id.menu_item_identities:
  		        Intent intent4 = new Intent(this, IdentitiesActivity.class);
  		        startActivity(intent4);
  		        break;
  		        
  		    case R.id.menu_item_addressBook:
  		        Intent intent5 = new Intent(this, AddressBookActivity.class);
  		        startActivity(intent5);
  		        break;
  		        
  		    case R.id.menu_item_settings:
  		        Intent intent6 = new Intent(this, SettingsActivity.class);
  		        startActivity(intent6);
  		        break;
  		        
  		    case R.id.menu_item_lock:
		    	AppLockHandler.runLockRoutine(mCacheWordHandler);
		        break;
  		        
  		    default:
  		        return super.onOptionsItemSelected(item);
  	    }

  	    return true;
  	}
      
      @Override
      protected void onStop()
      {
      	super.onStop();
      	if (mCacheWordHandler != null)
      	{
          	mCacheWordHandler.disconnectFromService();
      	}
       }
  	
  	@SuppressLint("InlinedApi")
  	@Override
  	public void onCacheWordLocked()
  	{
  		// Redirect to the lock screen activity
          Intent intent = new Intent(getBaseContext(), LockScreenActivity.class);
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) // FLAG_ACTIVITY_CLEAR_TASK only exists in API 11 and later 
          {
          	intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);// Clear the stack of activities
          }
          else
          {
          	intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          }
          startActivity(intent);
  	}

  	@Override
  	public void onCacheWordOpened()
  	{
  		// Nothing to do here currently
  	}
  	
  	@Override
  	public void onCacheWordUninitialized()
  	{
  		// Database encryption is currently not enabled by default, so there is nothing to do here
  	}
}