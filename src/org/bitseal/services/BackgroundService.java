package org.bitseal.services;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;

import org.bitseal.controllers.TaskController;
import org.bitseal.core.App;
import org.bitseal.core.QueueRecordProcessor;
import org.bitseal.data.Address;
import org.bitseal.data.Message;
import org.bitseal.data.Payload;
import org.bitseal.data.Pubkey;
import org.bitseal.data.QueueRecord;
import org.bitseal.database.AddressProvider;
import org.bitseal.database.MessageProvider;
import org.bitseal.database.PayloadProvider;
import org.bitseal.database.PubkeyProvider;
import org.bitseal.database.QueueRecordProvider;
import org.bitseal.network.NetworkHelper;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * This class handles all the long-running processing required
 * by the application. 
 * 
 * @author Jonathan Coe
 */
public class BackgroundService extends IntentService
{
	/**
	 * This constant determines whether or not the app will do
	 * proof of work for pubkeys and messages that it creates. 
	 * If not, it will expect servers to do the proof of work. 
	 */
	public static final boolean DO_POW = true;
	
	/**
	 * This 'maximum attempts' constant determines the number of times
	 * that a task will be attempted before it is abandoned and deleted
	 * from the queue.
	 */
	public static final int MAXIMUM_ATTEMPTS = 1000;
	
    /**
     * The normal amount of time in seconds between each attempt to start the
     * BackgroundService, in seconds. e.g. If this value is set to 60, then
     * a PendingIntent will be registed with the AlarmManager to start the
     * background service every minute. 
     */
	public static final int BACKGROUND_SERVICE_NORMAL_START_INTERVAL = 60;
	
    /**
     * The shorter period of time in seconds between each attempt to start the
     * BackgroundService, in seconds. This is used when we are still significantly
     * behind in catching up with the Bitmessage network. 
     */
	public static final int BACKGROUND_SERVICE_SHORT_START_INTERVAL = 5;
	
	/** Determines how often the database cleaning routine should be run, in seconds. */
	private static final long TIME_BETWEEN_DATABASE_CLEANING = 3600;
	
	/** A key used to store the time of the last successful 'check for new msgs' server request */
	private static final String LAST_MSG_CHECK_TIME = "lastMsgCheckTime";
	
	// Constants to identify requests from the UI
	public static final String UI_REQUEST = "uiRequest";
	public static final String UI_REQUEST_SEND_MESSAGE = "sendMessage";
	public static final String UI_REQUEST_CREATE_IDENTITY = "createIdentity";
	
	// Used when broadcasting Intents to the UI so that it can refresh the data it is displaying
	public static final String UI_NOTIFICATION = "uiNotification";
	
	// Constants that identify request for periodic background processing
	public static final String PERIODIC_BACKGROUND_PROCESSING_REQUEST = "periodicBackgroundProcessingReqest";
	public static final String BACKGROUND_PROCESSING_REQUEST = "doBackgroundProcessing";
	
	// Constants to identify data sent to this Service from the UI
	public static final String MESSAGE_ID = "messageId";
	public static final String ADDRESS_ID = "addressId";
	
	// The tasks for performing the first major function of the application: creating a new identity
	public static final String TASK_CREATE_IDENTITY = "createIdentity";
	public static final String TASK_DISSEMINATE_PUBKEY = "disseminatePubkey";
	
	// The tasks for performing the second major function of the application: sending messages
	public static final String TASK_SEND_MESSAGE = "sendMessage";
	public static final String TASK_PROCESS_OUTGOING_MESSAGE = "processOutgoingMessage";
	public static final String TASK_DISSEMINATE_MESSAGE = "disseminateMessage";
	
	// The tasks for performing the third major function of the application: receiving messages
	public static final String TASK_CHECK_FOR_MESSAGES_AND_SEND_ACKS = "checkServerForMessagesAndSendAcks";
	public static final String TASK_PROCESS_INCOMING_MSGS_AND_SEND_ACKS = "processIncomingMsgsAndSendAcks";
	public static final String TASK_SEND_ACKS = "sendAcks";
	
	// The tasks for performing the fourth major function of the application: periodically re-disseminating our pubkeys
	public static final String TASK_CHECK_IF_PUBKEY_RE_DISSEMINATION_IS_DUE = "checkIfPubkeyReDisseminationIsDue";
	public static final String TASK_RE_DISSEMINATE_PUBKEYS = "reDisseminatePubkeys";
	
	private static final String TAG = "BACKGROUND_SERVICE";
	
	public BackgroundService() 
	{
		super("BackgroundService");
	}
	
	/**
	 * Handles requests sent to the BackgroundService via Intents
	 * 
	 * @param - An Intent object that has been received by the 
	 * BackgroundService
	 */
	@Override
	protected void onHandleIntent(Intent i)
	{
		Log.i(TAG, "BackgroundService.onHandleIntent() called");
		
		// Determine whether the intent came from a request for periodic
		// background processing or from a ui request
		if (i.hasExtra(PERIODIC_BACKGROUND_PROCESSING_REQUEST))
		{
			processTasks();
		}
		
		else if (i.hasExtra(UI_REQUEST))
		{
			String uiRequest = i.getStringExtra(UI_REQUEST);
			
			TaskController taskController = new TaskController();
			
			if (uiRequest.equals(UI_REQUEST_SEND_MESSAGE))
			{
				Log.i(TAG, "Responding to UI request to run the 'send message' task");
				
				// Get the ID of the Message object from the intent
				Bundle extras = i.getExtras();
				long messageID = extras.getLong(MESSAGE_ID);
				
				// Attempt to retrieve the Message from the database. If it has been deleted by the user
				// then we should abort the sending process. 
				Message messageToSend = null;
				try
				{
					MessageProvider msgProv = MessageProvider.get(getApplicationContext());
					messageToSend = msgProv.searchForSingleRecord(messageID);
				}
				catch (RuntimeException e)
				{
					Log.i(TAG, "While running BackgroundService.onHandleIntent() and attempting to process a UI request of type\n"
							+ UI_REQUEST_SEND_MESSAGE + ", the attempt to retrieve the Message object from the database failed.\n"
							+ "The message sending process will therefore be aborted.");
					return;
				}
								
				// Create a new QueueRecord for the 'send message' task and save it to the database
				QueueRecordProcessor queueProc = new QueueRecordProcessor();
				QueueRecord queueRecord = queueProc.createAndSaveQueueRecord(TASK_SEND_MESSAGE, messageToSend, null);
				
				// First check whether an Internet connection is available. If not, the QueueRecord which records the 
				// need to send the message will be stored (as above) and processed later
				if (NetworkHelper.checkInternetAvailability() == true)
				{
					// Attempt to send the message
					taskController.sendMessage(queueRecord, messageToSend, DO_POW);
				}
			}
			
			else if (uiRequest.equals(UI_REQUEST_CREATE_IDENTITY))
			{
				Log.i(TAG, "Responding to UI request to run the 'create new identity' task");
				
				// Get the ID of the Address object from the intent
				Bundle extras = i.getExtras();
				long addressId = extras.getLong(ADDRESS_ID);
				
				// Attempt to retrieve the Address from the database. If it has been deleted by the user
				// then we should abort the sending process. 
				Address address = null;
				try
				{
					AddressProvider addProv = AddressProvider.get(getApplicationContext());
					address = addProv.searchForSingleRecord(addressId);
				}
				catch (RuntimeException e)
				{
					Log.i(TAG, "While running BackgroundService.onHandleIntent() and attempting to process a UI request of type\n"
							+ UI_REQUEST_CREATE_IDENTITY + ", the attempt to retrieve the Address object from the database failed.\n"
							+ "The identity creation process will therefore be aborted.");
					return;
				}
				
				// Create a new QueueRecord for the create identity task and save it to the database
				QueueRecordProcessor queueProc = new QueueRecordProcessor();
				QueueRecord queueRecord = queueProc.createAndSaveQueueRecord(TASK_CREATE_IDENTITY, address, null);
				
				// Attempt to complete the create identity task
				taskController.createIdentity(queueRecord, DO_POW);
			}
		}
		else
		{
			Log.e(TAG, "BackgroundService.onHandleIntent() was called without a valid extra to specify what the service should do.");
		}
		
		// Create a new intent that will be used to run processTasks() again after a period of time
		Intent intent = new Intent(getApplicationContext(), BackgroundService.class);
		intent.putExtra(BackgroundService.PERIODIC_BACKGROUND_PROCESSING_REQUEST, BackgroundService.BACKGROUND_PROCESSING_REQUEST);
		PendingIntent pendingIntent = PendingIntent.getService(getApplicationContext(), 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
		
	    // Get the current time and add the number of seconds specified by BACKGROUND_SERVICE_START_INTERVAL_SECONDS to it
	    Calendar cal = Calendar.getInstance();
	    
		// Check whether we are significantly behind in checking for new msgs. If we are AND there is an internet connection available
	    // then we should restart the background service almost immediately. The small time gap allows a window for user-initiated tasks
	    // such as sending a message to be started.
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(App.getContext());
		long lastMsgCheckTime = prefs.getLong(LAST_MSG_CHECK_TIME, 0);
		long currentTime = System.currentTimeMillis() / 1000;
	    if (((currentTime - lastMsgCheckTime) > BACKGROUND_SERVICE_NORMAL_START_INTERVAL) && (NetworkHelper.checkInternetAvailability() == true))
	    {
	    	cal.add(Calendar.SECOND, BACKGROUND_SERVICE_SHORT_START_INTERVAL);
		    Log.i(TAG, "The BackgroundService will be restarted in " + BACKGROUND_SERVICE_SHORT_START_INTERVAL + " seconds");
	    }
	    else
	    {
	    	cal.add(Calendar.SECOND, BACKGROUND_SERVICE_NORMAL_START_INTERVAL);
	    	Log.i(TAG, "The BackgroundService will be restarted in " + BACKGROUND_SERVICE_NORMAL_START_INTERVAL + " seconds");
	    }
	    
	    // Register the pending intent with AlarmManager
	    AlarmManager am = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
	    am.set(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pendingIntent);
	}
	
	/**
	 * Runs periodic background processing. <br><br>
	 * 
	 * This method will first check whether there are any QueueRecord objects saved
	 * in the database. If there are, it will attempt to complete the task recorded
	 * by each of those QueueRecords in turn. After that, it will run the 'check for
	 * messages' task. If no QueueRecords are found in the database, it will run the
	 * 'check for messages' task. 
	 */
	private void processTasks()
	{
		Log.i(TAG, "BackgroundService.processTasks() called");
		
		TaskController taskController = new TaskController();
		
		// Check the database TaskQueue table for any queued tasks
		QueueRecordProvider queueProv = QueueRecordProvider.get(getApplicationContext());
		
		ArrayList<QueueRecord> queueRecords = queueProv.getAllQueueRecords();
		Log.i(TAG, "Number of QueueRecords found: " + queueRecords.size());
		if (queueRecords.size() > 0)
		{
			// Sort the queue records so that we will process the records with the earliest 'last attempt time' first
			Collections.sort(queueRecords);
			
			// Process each queued task in turn, removing them from the database if completed successfully
			for (QueueRecord q : queueRecords)
			{
				Log.i(TAG, "Found a QueueRecord with the task " + q.getTask() + " and number of attempts " + q.getAttempts());
				
				// First check how many times the task recorded by this QueueRecord has been attempted.
				// If it has been attempted a very high number of times (all without success) then we
				// will delete it.
				int attempts = q.getAttempts();
				String task = q.getTask();
				if (attempts > MAXIMUM_ATTEMPTS)
				{
					if (task.equals(TASK_SEND_MESSAGE))
					{
						MessageProvider msgProv = MessageProvider.get(getApplicationContext());
						Message messageToSend = msgProv.searchForSingleRecord(q.getObject0Id());
						messageToSend.setStatus(Message.STATUS_SENDING_FAILED);
						msgProv.updateMessage(messageToSend);
					}
					QueueRecordProcessor queueProc = new QueueRecordProcessor();
					queueProc.deleteQueueRecord(q);
					continue;
				}
				
				if (task.equals(TASK_SEND_MESSAGE))
				{
					// Check whether an Internet connection is available. If not, move on to the next QueueRecord
					if (NetworkHelper.checkInternetAvailability() == true)
					{
						// Attempt to retrieve the Message from the database. If it has been deleted by the user
						// then we should abort the sending process. 
						try
						{
							MessageProvider msgProv = MessageProvider.get(getApplicationContext());
							Message messageToSend = msgProv.searchForSingleRecord(q.getObject0Id());
							taskController.sendMessage(q, messageToSend, DO_POW);
						}
						catch (RuntimeException e)
						{
							Log.i(TAG, "While running BackgroundService.processTasks() and attempting to process a task of type\n"
									+ TASK_SEND_MESSAGE + ", the attempt to retrieve the Message object from the database failed.\n"
									+ "The message sending process will therefore be aborted.");
							queueProv.deleteQueueRecord(q);
							continue;
						}
					}
				}
				
				else if (task.equals(TASK_PROCESS_OUTGOING_MESSAGE))
				{
					// Attempt to retrieve the Message from the database. If it has been deleted by the user
					// then we should abort the sending process. 
					Message messageToSend = null;
					try
					{
						MessageProvider msgProv = MessageProvider.get(getApplicationContext());
						messageToSend = msgProv.searchForSingleRecord(q.getObject0Id());
					}
					catch (RuntimeException e)
					{
						Log.i(TAG, "While running BackgroundService.processTasks() and attempting to process a task of type\n"
								+ TASK_PROCESS_OUTGOING_MESSAGE + ", the attempt to retrieve the Message object from the database failed.\n"
								+ "The message sending process will therefore be aborted.");
						queueProv.deleteQueueRecord(q);
						continue;
					}
					 
					// Now retrieve the pubkey for the address we are sending the message to
					PubkeyProvider pubProv = PubkeyProvider.get(App.getContext());
					Pubkey toPubkey = pubProv.searchForSingleRecord(q.getObject1Id());
						 
					// Attempt to process and send the message
					taskController.processOutgoingMessage(q, messageToSend, toPubkey, DO_POW);
				}
				
				else if (task.equals(TASK_DISSEMINATE_MESSAGE))
				{
					// First check whether an Internet connection is available. If not, move on to the next QueueRecord
					if (NetworkHelper.checkInternetAvailability() == true)
					{
						PayloadProvider payProv = PayloadProvider.get(getApplicationContext());
						Payload payloadToSend = payProv.searchForSingleRecord(q.getObject0Id());
						 
						// Now retrieve the pubkey for the address we are sending the message to
						PubkeyProvider pubProv = PubkeyProvider.get(App.getContext());
						Pubkey toPubkey = pubProv.searchForSingleRecord(q.getObject1Id());
							 
						// Attempt to process and send the message
						taskController.disseminateMessage(q, payloadToSend, toPubkey, DO_POW);
					}
				}
				
				else if (task.equals(TASK_PROCESS_INCOMING_MSGS_AND_SEND_ACKS))
				{
					int newMessagesProcessed = taskController.processIncomingMessages();
					if (newMessagesProcessed > 0)
					{
					    Intent intent = new Intent(getBaseContext(), NotificationsService.class);
					    intent.putExtra(NotificationsService.EXTRA_DISPLAY_NEW_MESSAGES_NOTIFICATION, newMessagesProcessed);
					    startService(intent);
					}
				}
				
				else if (task.equals(TASK_SEND_ACKS))
				{
					// First check whether an Internet connection is available. If not, move on to the next QueueRecord
					if (NetworkHelper.checkInternetAvailability() == true)
					{
						taskController.sendAcknowledgments(q);
					}
				}
				
				else if (task.equals(TASK_CREATE_IDENTITY))
				{
					taskController.createIdentity(q, DO_POW);
				}
				
				else if (task.equals(TASK_DISSEMINATE_PUBKEY))
				{
					// First check whether an Internet connection is available. If not, move on to the next QueueRecord
					if (NetworkHelper.checkInternetAvailability() == true)
					{
						PayloadProvider payProv = PayloadProvider.get(getApplicationContext());
						Payload payloadToSend = payProv.searchForSingleRecord(q.getObject0Id());
						taskController.disseminatePubkey(q, payloadToSend, DO_POW);
					}
				}
				
				else
				{
					Log.e(TAG, "While running BackgroundService.processTasks(), a QueueRecord with an invalid task " +
							"field was found. The invalid task field was : " + task);
				}
			}
			// Once we have attempted all queued tasks once
			runCheckForMessagesTask();
			runCheckIfPubkeyReDisseminationIsDueTask();
		}
		else // If there are no other tasks that we need to do
		{
			runCheckForMessagesTask();
			runCheckIfPubkeyReDisseminationIsDueTask();
			
			// Check whether it is time to run the 'clean database' routine. If yes then run it. 
			if (checkIfDatabaseCleaningIsRequired())
			{
				Intent intent = new Intent(getBaseContext(), DatabaseCleaningService.class);
			    intent.putExtra(DatabaseCleaningService.EXTRA_RUN_DATABASE_CLEANING_ROUTINE, true);
			    startService(intent);
			}
		}
	}
	
	/**
	 * This method runs the 'check for messages and send acks' task, via
	 * the TaskController. <br><br>
	 * 
	 * Note that we do NOT create QueueRecords for this task, because it
	 * is a default action that will be carried out regularly anyway.
	 */
	private void runCheckForMessagesTask()
	{
		// First check whether an Internet connection is available. If not, we cannot proceed. 
		if (NetworkHelper.checkInternetAvailability() == true)
		{
			// Only run this task if we have at least one Address!
			AddressProvider addProv = AddressProvider.get(getApplicationContext());
			ArrayList<Address> myAddresses = addProv.getAllAddresses();
			if (myAddresses.size() > 0)
			{
				// Attempt to complete the task
				TaskController taskController = new TaskController();
				int newMessagesProcessed = taskController.checkForMessagesAndSendAcks();
				if (newMessagesProcessed > 0)
				{
				    Intent intent = new Intent(getBaseContext(), NotificationsService.class);
				    intent.putExtra(NotificationsService.EXTRA_DISPLAY_NEW_MESSAGES_NOTIFICATION, newMessagesProcessed);
				    startService(intent);
				}
			}
			else
			{
				Log.i(TAG, "No Addresses were found in the application database, so we will not run the 'Check for messages' task");
			}
		}
	}
	
	/**
	 * This method runs the 'check if pubkey re-dissemination is due' task, via
	 * the TaskController. <br><br>
	 * 
	 * Note that we do NOT create QueueRecords for this task, because it is a
	 * default action that will be carried out regularly anyway. 
	 */
	private void runCheckIfPubkeyReDisseminationIsDueTask()
	{
		// First check whether an Internet connection is available. If not, we cannot proceed. 
		if (NetworkHelper.checkInternetAvailability() == true)
		{		
			// Only run this task if we have at least one Address!
			AddressProvider addProv = AddressProvider.get(getApplicationContext());
			ArrayList<Address> myAddresses = addProv.getAllAddresses();
			if (myAddresses.size() > 0)
			{
				// Attempt to complete the task
				TaskController taskController = new TaskController();
				taskController.checkIfPubkeyDisseminationIsDue(DO_POW);
			}
			else
			{
				Log.i(TAG, "No Addresses were found in the application database, so we will not run the 'Check if pubkey re-dissemination is due' task");
			}
		}
	}
	
	/**
	 * Determines whether it is time to run the 'clean database' routine,
	 * which deletes defunct data. This is based on the period of time since
	 * this routine was last run. 
	 * 
	 * @return A boolean indicating whether or not the 'clean database' routine
	 * should be run.
	 */
	private boolean checkIfDatabaseCleaningIsRequired()
	{	
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		long currentTime = System.currentTimeMillis() / 1000;
		long lastDataCleanTime = prefs.getLong(DatabaseCleaningService.LAST_DATABASE_CLEAN_TIME, 0);
		
		if (lastDataCleanTime == 0)
		{
			return true;
		}
		else
		{
			long timeSinceLastDataClean = currentTime - lastDataCleanTime;
			if (timeSinceLastDataClean > TIME_BETWEEN_DATABASE_CLEANING)
			{
				return true;
			}
			else
			{
				long timeTillNextDatabaseClean = TIME_BETWEEN_DATABASE_CLEANING - timeSinceLastDataClean;
				Log.i(TAG, "The database cleaning service was last run " + timeSinceLastDataClean + " seconds ago. It will be run again in " + timeTillNextDatabaseClean + " seconds.");
				return false;
			}
		}
	}
}