package com.google.appinventor.components.runtime;


import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.accounts.AccountManager;
import android.app.Activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;

import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.DesignerProperty;
import com.google.appinventor.components.annotations.PropertyCategory;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleProperty;
import com.google.appinventor.components.annotations.UsesLibraries;
import com.google.appinventor.components.annotations.UsesPermissions;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.common.PropertyTypeConstants;
import com.google.appinventor.components.common.YaVersion;
import com.google.appinventor.components.runtime.util.AsynchUtil;
import com.google.appinventor.components.runtime.util.OAuth2Helper;
import com.google.gson.JsonElement;

import edu.mit.media.funf.FunfManager;
import edu.mit.media.funf.pipeline.Pipeline;
import edu.mit.media.funf.storage.UploadService;

//TODO: rename this component and the dropbox component to GoogleDriveUploader
@DesignerComponent(version = YaVersion.GOOGLE_DRIVE_COMPONENT_VERSION,
    description = "This component can upload file(s) to Google Drive.",
    category = ComponentCategory.FUNF,
    nonVisible = true,
    iconName = "images/googledrive.png")
@UsesPermissions(permissionNames = "android.permission.GET_ACCOUNTS," +
    "android.permission.INTERNET," +
    "android.permission.WAKE_LOCK, " +
    "android.permission.WRITE_EXTERNAL_STORAGE, " +
    "android.permission.READ_LOGS, " + 
    "android.permission.ACCESS_NETWORK_STATE")
@UsesLibraries(libraries =
   "google-http-client-beta.jar," +
   "google-oauth-client-beta.jar," +
   "google-api-services-drive-v2.jar," +
   "google-api-client-beta.jar," +
   "google-api-client-android-beta-14.jar," +
   "google-http-client-android-beta-14.jar," +
   "google-http-client-gson-beta-14.jar, " +
   "google-play-services.jar," +
   "funf.jar")
public class GoogleDrive extends AndroidNonvisibleComponent
implements ActivityResultListener, Component, Pipeline, OnResumeListener{
  
  private static final String TAG = "GoogleDrive";

  protected boolean mIsBound = false;
  public static final String GOOGLEDRIVE_PIPE_NAME = "googledrive";
  
  private final ComponentContainer container;
  private final Handler handler;
  private final SharedPreferences sharedPreferences;
  public static final String PREFS_GOOGLEDRIVE = "googledrive_pref";
  public static final String PREF_ACCOUNT_NAME = "gd_account";
  public static final String PREF_AUTH_TOKEN = "gd_authtoken";
  public final static String GD_FOLDER = "gd_folder";
  public static final String DEFAULT_GD_FOLDER = "gd_root";

  
  protected static Activity mainUIThreadActivity;
  private final int REQUEST_CHOOSE_ACCOUNT; 
  private final int REQUEST_AUTHORIZE;
  //for testing purpose
  private final int REQUEST_CAPTURE;
  private boolean firstime = true;
  ////////////////
  private String gdFolder;
  
  //binding to GoogleDriveUploadService and FunfManager Service
  
  protected GoogleDriveUploadService mBoundGDService= null;
  protected FunfManager mBoundFunfManager = null;
  protected static final String ACTION_UPLOAD_DATA = "UPLOAD_DATA";
  
  private static final long SCHEDULE_UPLOAD_PERIOD = 7200; //default period for uploading task 


  private AccessToken accessTokenPair;
  
  private long upload_period;
  private boolean wifiOnly = false;
  
  private static Drive service;
  private GoogleAccountCredential credential;

  // try local binding to FunfManager
  private ServiceConnection mConnection = new ServiceConnection() {
    public void onServiceConnected(ComponentName className, IBinder service) {

      mBoundFunfManager = ((FunfManager.LocalBinder) service)
          .getManager();
      
      registerSelfToFunfManager(); 

      Log.i(TAG, "Bound to FunfManager");

    }

    public void onServiceDisconnected(ComponentName className) {
      mBoundFunfManager = null;

      Log.i(TAG, "Unbind FunfManager");

    }
  };
  
  /*
   * GoogleDrive component, similar the dropbox component, will be bound to two services 
   * 1. FunfManager service(for scheduling repeating tasks)
   * 2. GoogleUploadService (for uploading the data to Google Drive)
   */
  
  public GoogleDrive(ComponentContainer container) {
    // TODO Auto-generated constructor stub
    super(container.$form());
    this.container = container;
    handler = new Handler();
    sharedPreferences = container.$context().getSharedPreferences(PREFS_GOOGLEDRIVE, Context.MODE_PRIVATE);
    accessTokenPair = retrieveAccessToken();
    mainUIThreadActivity = container.$context();
    Log.i(TAG, "Package name:" + mainUIThreadActivity.getApplicationContext().getPackageName());
    // start a FunfManager Service
    Intent i = new Intent(mainUIThreadActivity, FunfManager.class);
    mainUIThreadActivity.startService(i);

    // bind to FunfManger (in case the user wants to set up the
    // schedule)
    doBindService();
    this.upload_period = GoogleDrive.SCHEDULE_UPLOAD_PERIOD;
    REQUEST_CHOOSE_ACCOUNT = form.registerForActivityResult(this);
    REQUEST_AUTHORIZE = form.registerForActivityResult(this);
    REQUEST_CAPTURE =  form.registerForActivityResult(this);
    this.gdFolder = GoogleDrive.DEFAULT_GD_FOLDER;
    //try to use getApplicationContext for authorizing the credential, because when Service is running, it's using
    //getApplicationContext
    credential = GoogleAccountCredential.usingOAuth2(mainUIThreadActivity, DriveScopes.DRIVE);
    
    
  }
  void doBindService() {

    mainUIThreadActivity.bindService(new Intent(mainUIThreadActivity,
        FunfManager.class), mConnection, Context.BIND_AUTO_CREATE);
    mIsBound = true;
    Log.i(TAG,
        "FunfManager is bound, and now we could have register dataRequests");
    
//    mainUIThreadActivity.bindService(new Intent(mainUIThreadActivity,
//        GoogleDriveUploadService.class), mConnectionGD, Context.BIND_AUTO_CREATE);
//    
//    Log.i(TAG,
//
//    "GoogleDriveUploadService is bound, and now we could register for GoogleDriveException Listener");

  }
  
  void doUnbindService() {
    if (mIsBound) {
      // unregister Pipeline action 
      unregisterPipelineActions();
      // Detach our existing connection.
      mainUIThreadActivity.unbindService(mConnection);
      mIsBound = false;
    }
  }
  
  
  public void unregisterPipelineActions() {
    // TODO Auto-generated method stub
     mBoundFunfManager.unregisterPipelineAction(this, ACTION_UPLOAD_DATA);   
    
  }

  @Override
  public void onResume() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void onCreate(FunfManager manager) {
    // FunfManager manager
    // This function will run once whenever FunfManager.registerPipeline() is called
    //do nothing for now
    
  }

  @Override
  public void onDestroy() {
    // TODO Auto-generated method stub
    doUnbindService();
  }

  @Override
  public void onRun(String arg0, JsonElement arg1) {
    // TODO Auto-generated method stub
  	
  }

  @Override
  public void resultReturned(int requestCode, int resultCode, Intent data) {
    // When the authentication from Google chooseAccount is back
    Log.i(TAG, "resultReturned.... " + resultCode);
    final Intent returnData = data;
    // if it's returning from choose account
    if (requestCode == REQUEST_CHOOSE_ACCOUNT) {
      if (resultCode == Activity.RESULT_OK && data != null
          && data.getExtras() != null) {
        
        setUpDriveService(data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME));
               
      } else {
        // TODO: Should not happen
      }

    }// Below happens when after UserRecoverableAuthException 
    if (requestCode == REQUEST_AUTHORIZE) {
      if (resultCode == Activity.RESULT_OK) {

        String accountName = data
            .getStringExtra(AccountManager.KEY_ACCOUNT_NAME);

        String accessToken = data.getStringExtra(AccountManager.KEY_AUTHTOKEN);
        saveAccessToken(new AccessToken(accountName, accessToken));

      } else {
        mainUIThreadActivity.startActivityForResult(credential.newChooseAccountIntent(), REQUEST_CHOOSE_ACCOUNT);
      }

     /// testing code///
    }
    if(requestCode == REQUEST_CAPTURE){
      if (resultCode == Activity.RESULT_OK){
        saveFileToDrive();

      }
    }
    
    
  }
  
/*
 * This should only be done once in the main UI. Once we have authorized the app, the 
 * GoogleAccountCredential will automatically refresh token with Google Play service if it expires
 */
  private void setUpDriveService(String accountName) {

    final String mAccountName = accountName;
    AsynchUtil.runAsynchronously(new Runnable() {
      public void run() {
        String token = "";
        credential.setSelectedAccountName(mAccountName);
        try {
          Log.i(TAG, "before getToken()... ");
          token = credential.getToken();
        } catch (UserRecoverableAuthException e) {
          // if the user has not yet authorized
          Log.i(TAG, "in userRecoverableAuthExp... ");
          // this means that the user has never grant permission to this app before
          UserRecoverableAuthException exception = (UserRecoverableAuthException) e;
          Intent authorizationIntent = exception.getIntent();
          mainUIThreadActivity.startActivityForResult(authorizationIntent,
              REQUEST_AUTHORIZE);
        } catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        } catch (GoogleAuthException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        Log.i(TAG, "before build drive service... ");
        saveAccessToken(new AccessToken(mAccountName, token));
        service = new Drive.Builder(AndroidHttp.newCompatibleTransport(),
            new GsonFactory(), credential).build();
        Log.i(TAG, "after drive service... ");
        // tell the mainUI that we are done
        AsynchUtil.runAsynchronously(new Runnable() {
          public void run() {

            handler.post(new Runnable() {
              @Override
              public void run() {
                IsAuthorized();
              }
            });

          }
        });
      }
    });
  } 
  
  /**
   * Indicates when the authorization has been successful.
   */
  @SimpleEvent(description =
               "This event is raised after the program calls " +
               "<code>Authorize</code> if the authorization was successful.  " +
               "Only after this event has been raised or CheckAuthorize() returns True," +
               " any other method for this " +
               "component can be called.")
  public void IsAuthorized() {
    Log.i(TAG, "call isAuthorized");
    EventDispatcher.dispatchEvent(this, "IsAuthorized");
  }
  
//  private void registerExceptionListener() {
//    
//    this.mBoundGDService.registerException(listener);
//  }
  
  /*
   * After we bind to FunfManger, we have to register self to Funf as a Pipeline. 
   * This is for later to be wakened up and do previously registered actions 
   */
  private void registerSelfToFunfManager(){
    Log.i(TAG, "register this class as a Pipeline to FunfManger");
    mBoundFunfManager.registerPipeline(GOOGLEDRIVE_PIPE_NAME, this);
    
  }

  GoogleDriveExceptionListener listener = new GoogleDriveExceptionListener() {
    @Override
    public void onExceptionReceived(Exception e) {
    }  
  };
  
  /**
   * Indicates whether the user has specified that the component could only 
   * use Wifi to upload file(s). If this value is set to False, the GoogleDrive
   * uploader will use either Wifi or 3G/4G dataservice, whichever is available.
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_BOOLEAN, defaultValue = "False")
  @SimpleProperty(description = "If this value is set to False, the GoogleDrive " +
  		"uploader will use either Wifi or 3G/4G dataservice, whichever is available")
  public void WifiOnly(boolean wifiOnly) {

    if (this.wifiOnly != wifiOnly)
      this.wifiOnly = wifiOnly;

  }
  
  /**
   * Indicates whether the user has specified that the GoogleDrive component could only 
   * use Wifi to upload data
   */
  @SimpleProperty(category = PropertyCategory.BEHAVIOR, description = "uploader will" +
  		" use either Wifi or 3G/4G dataservice, whichever is available")
  public boolean WifiOnly() {
    return wifiOnly;
  }
  
  /**
   * Start OAuth2 Authentication to ask for user's permission for using Google Drive
   */
  @SimpleFunction(
      description = "Start the Authorization process to ask the user for permission to access" +
      		"his or her Google Drive. Need to do it at least once, before using the " +
      		"Google Drive APIs")
  public void Authorize() {
    // we will not use OAuth2Helper here, the newest version of Google-api has taken care the flow for us
    // This will help us getting the main Google Account name, and auth token for the first time
    // we will persist these two in sharedPreference
    Log.i(TAG, "Start Authorization");

  	// check if we have choose the account already
  	String accountName = sharedPreferences.getString(PREF_ACCOUNT_NAME, "");
  	if(accountName.length() == 0){
  	  mainUIThreadActivity.startActivityForResult(credential.newChooseAccountIntent(), REQUEST_CHOOSE_ACCOUNT);
  	}
  	else{
  	  setUpDriveService(accountName);
  	}
    
  }
  
  /**
   * Check whether we already have already the Google Drive access token
   */
  @SimpleFunction(
      description = "Checks whether we already have access token already, " +
      		"if so, return True")
  public boolean CheckAuthorized() {
    String accountName =  accessTokenPair.accountName;
    String token =  accessTokenPair.accountName;
    if (accountName.length() == 0 || token.length() == 0) {
      return false;
    }
    else
      return true;

  } 
  
  /**
   * Remove authentication for this app instance
   */
  @SimpleFunction(
      description = "Removes Google Drive authorization from this running app instance")
  public void DeAuthorize() {
    accessTokenPair = null;
    saveAccessToken(accessTokenPair);
  } 
  
  private void saveAccessToken(AccessToken accessToken) {
    final SharedPreferences.Editor sharedPrefsEditor = sharedPreferences.edit();
    if (accessToken == null) {
      sharedPrefsEditor.remove(PREF_ACCOUNT_NAME);
      sharedPrefsEditor.remove(PREF_AUTH_TOKEN);
    } else {
      sharedPrefsEditor.putString(PREF_ACCOUNT_NAME, accessToken.accountName);
      sharedPrefsEditor.putString(PREF_AUTH_TOKEN, accessToken.accessToken);
      Log.i(TAG, "Save Google Access Token and Account" + accessToken.accountName + ", " + accessToken.accessToken);
    }
    sharedPrefsEditor.commit();
  }
  
  
  public class AccessToken  {
    public final String accessToken;
    public final String accountName;

    public AccessToken(String accountName, String accessToken) {
        this.accountName= accountName;
        this.accessToken = accessToken;
    }
}
  private AccessToken retrieveAccessToken() {
    String accountName = sharedPreferences.getString(OAuth2Helper.PREF_ACCOUNT_NAME, "");
    String accessToken = sharedPreferences.getString(OAuth2Helper.PREF_AUTH_TOKEN, "");
    if (accountName.length() == 0 || accessToken.length() == 0) {
      return new AccessToken("",""); // returning an accessToken with both params empty
    }
    return new AccessToken(accountName, accessToken);
  }
  
  /*
   * Set the Google Drive folder to which the file(s) will be uploaded 
   */
  
  @SimpleProperty(description = "Set up the Google Drive" +
      "folder in which the uploaded file(s) will be placed. If not set, " +
      "default will be the root of Google Drive" +
      "", category = PropertyCategory.BEHAVIOR )
    public void GoogleDriveFolder(String folderName){
    
    this.gdFolder = folderName;    
    final SharedPreferences.Editor sharedPrefsEditor = sharedPreferences.edit();   
    sharedPrefsEditor.putString(GoogleDrive.GD_FOLDER , this.gdFolder);
    sharedPrefsEditor.commit();
  }
  
  /*
   * Indicate the folder(directory) to where the uploaded file(s) will be placed
   */

  @SimpleProperty(description = "Return the Google Drive folder(directory) to where the uploaded" +
      "file(s) will be placed", category = PropertyCategory.BEHAVIOR )
  public String GoogleDriveFolder(){
    return this.gdFolder;
    
  }

  public Class<? extends UploadService> getUploadServiceClass() {
    return GoogleDriveUploadService.class;
  }
  
  /*
   * Upload a file to Google Drive
   */
  @SimpleFunction(description = "This function uploads the file " +
      "(as specified with its filepath) to Google Drive folder. ")
      
  public void UploadData(String filename) {
    //TODO: use MediaUtil.java to know about the file type and how to deal with it
    // this will be the archive file name 
    //This method uploads the specified file directly to GoogleDrive
    
    String archiveName = filename;
    Log.i(TAG, "Start uploadService...");
    Intent i = new Intent(mainUIThreadActivity, getUploadServiceClass());
    i.putExtra(UploadService.ARCHIVE_ID, archiveName);
    i.putExtra(GoogleDrive.GD_FOLDER, this.gdFolder);
    i.putExtra(GoogleDriveUploadService.FILE_TYPE, GoogleDriveUploadService.REGULAR_FILE);
    i.putExtra(UploadService.REMOTE_ARCHIVE_ID, GoogleDriveArchive.GOOGLEDRIVE_ID);
    i.putExtra(UploadService.NETWORK,(this.wifiOnly) ? UploadService.NETWORK_WIFI_ONLY
          : UploadService.NETWORK_ANY);
    mainUIThreadActivity.startService(i);

  }
  // below is the code to test if we can at least upload one photo?
  ///////////////////////////////////////////////////////////////////////////////////////////////////
  private static Uri fileUri;


  private void startCameraIntent() {
    String mediaStorageDir = Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_PICTURES).getPath();
    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    fileUri = Uri.fromFile(new java.io.File(mediaStorageDir + java.io.File.separator + "IMG_"
        + timeStamp + ".jpg"));

    Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, fileUri);
    mainUIThreadActivity.startActivityForResult(cameraIntent, REQUEST_CAPTURE);
  }

  private void saveFileToDrive() {
    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          // File's binary content
          java.io.File fileContent = new java.io.File(fileUri.getPath());
          FileContent mediaContent = new FileContent("image/jpeg", fileContent);

          // File's metadata.
          File body = new File();
          body.setTitle(fileContent.getName());
          body.setMimeType("image/jpeg");

          File file = service.files().insert(body, mediaContent).execute();
 
          
          if (file != null) {
            showToast("Photo uploaded: " + file.getTitle());
          }
        } catch (UserRecoverableAuthIOException e) {
          Log.i(TAG, "Are we ever here? saveFileToDrive@GoogleDrive");
          mainUIThreadActivity.startActivityForResult(e.getIntent(), REQUEST_AUTHORIZE);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    });
    t.start();
  }
  
  public void showToast(final String toast) {
    mainUIThreadActivity.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        Toast.makeText(mainUIThreadActivity.getApplicationContext(), toast, Toast.LENGTH_SHORT).show();
      }
    });
  }
  @SimpleFunction
  public void TestUploadPhoto(){
    startCameraIntent();
    
  }
  
}
