package com.main.chatmate;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.FileUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.firebase.FirebaseException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

public class LoginActivity extends AppCompatActivity {
	private EditText numero, code_field;
	private Button sendCode, login;
	private TextView infoField, resendCode, resendTimer;
	private CountDownTimer timer;
	
	private final long codeInterval = 120L;
	
	private String mVerificationId;
	private PhoneAuthProvider.ForceResendingToken mToken;
	private boolean codeSent = false;
	private final PhoneAuthProvider.OnVerificationStateChangedCallbacks mCallbacks = initCallback();
	
	@Override
	protected void onStart() {
		super.onStart();
		
		FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser(); // utente già loggato, easy
		if (user != null) {
			MyLogger.log("User logged in");
			Intent intent = new Intent(LoginActivity.this, MainActivity.class);
			startActivity(intent);
		}
		// utente non loggato, oh shiet
		MyLogger.log("Login activity started successfully");
	}
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_login);
		
		numero = findViewById(R.id.login_phoneNumber_editText);
		sendCode = findViewById(R.id.login_sendVerificationCode_button);
		code_field = findViewById(R.id.login_verificationCode_editText);
		login = findViewById(R.id.login_checkVerificationCode_button);
		infoField = findViewById(R.id.login_info_textView);
		resendCode = findViewById(R.id.login_resendCode_textView);
		resendTimer = findViewById(R.id.login_timer_textView);
		
		sendCode.setOnClickListener(this::sendCode);
		login.setOnClickListener(this::login);
		resendCode.setOnClickListener(this::reSendCode);
		resendCode.setEnabled(false);
		
		FirebaseAuth.getInstance().getFirebaseAuthSettings().forceRecaptchaFlowForTesting(true);
		
		MyLogger.log("Login activity created successfully");
	}
	
	private void signInWithPhoneAuthCredential(PhoneAuthCredential credential) {
		FirebaseAuth.getInstance().signInWithCredential(credential).addOnCompleteListener(this, task -> {
			if (task.isSuccessful()) {
				// L'utente esiste yeee
				MyLogger.log("Login succeeded");
				
				FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
				assert user != null;
				user.reauthenticate(credential);
				StorageReference storageRef = FirebaseStorage.getInstance().getReference();
				
				final long ONE_MEGABYTE = 1024 * 1024;
				storageRef.child(user.getUid() + "/info.chatmate").getBytes(ONE_MEGABYTE).addOnSuccessListener(bytes -> {
					// Data for "UID/info.txt" is returned, use this as needed
					MyLogger.log("Retrieved user info");
					
					//TODO: class user
					
				}).addOnFailureListener(exception -> {
					byte[] data = "ciao\nSono giovanni".getBytes();
					
					UploadTask uploadTask = storageRef.child(user.getUid() + "/info.chatmate").putBytes(data);
					uploadTask.addOnFailureListener(failureException -> {
						// Handle unsuccessful uploads
						MyLogger.log("Can't upload user info to database: " + failureException.getMessage());
					}).addOnSuccessListener(taskSnapshot -> {
						// taskSnapshot.getMetadata() contains file metadata such as size, content-type, etc.
					});
					
					MyLogger.log("Created user info");
				});
				
				Intent intent = new Intent(LoginActivity.this, MainActivity.class);
				startActivity(intent);
			} else {
				if (task.getException() instanceof FirebaseAuthInvalidCredentialsException) {
					// The verification code entered was invalid
					infoField.setText(R.string.invalid_verification_code);
					code_field.setText("");
					MyLogger.log("Invalid Code");
				}
			}
		});
	}
	
	// TODO: dare l'opzione di scegliere il prefisso
	private void sendCode(View v) {
		// FORMATI SUPPORTATI CHE HO SCOPERTO:
		//+1 650-555-3434
		//+393479978847
		if(!numero.getText().toString().isEmpty()) {
			PhoneAuthOptions options = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
					.setPhoneNumber("+39" + numero.getText().toString())
					.setTimeout(codeInterval, TimeUnit.SECONDS) // tempo dopo il quale il codice di verifica scade
					.setActivity(this).setCallbacks(mCallbacks)
					.build();
			
			PhoneAuthProvider.verifyPhoneNumber(options);
			initTimer();
		}
		else {
			infoField.setText(R.string.insert_phone);
		}
	}
	
	private void reSendCode(View v) {
		// FORMATI SUPPORTATI CHE HO SCOPERTO:
		//+1 650-555-3434
		//+393479978847
		if(!numero.getText().toString().isEmpty()) {
			PhoneAuthOptions options = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
					.setPhoneNumber("+39" + numero.getText().toString())
					.setTimeout(120L, TimeUnit.SECONDS) // tempo dopo il quale il codice di verifica scade
					.setActivity(this).setCallbacks(mCallbacks)
					.setForceResendingToken(mToken)
					.build();
			
			PhoneAuthProvider.verifyPhoneNumber(options);
			resendCode.setEnabled(false);
			resendTimer.setText(getString(R.string.resend_code_in_x_seconds, codeInterval));
			initTimer();
		}
		else {
			infoField.setText(R.string.insert_phone);
		}
	}
	
	private void login(View v){
		if(!codeSent)
			return;
		
		String code = code_field.getText().toString();
		if(!code.isEmpty() && code.length() >= 6) {
			PhoneAuthCredential credential = PhoneAuthProvider.getCredential(mVerificationId, code);
			signInWithPhoneAuthCredential(credential);
		}
		else {
			infoField.setText(R.string.verification_code_lenght_error);
		}
	}
	
	private PhoneAuthProvider.OnVerificationStateChangedCallbacks initCallback(){
		return new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
			@Override
			public void onVerificationCompleted(PhoneAuthCredential credential) {
				MyLogger.log("Verification completed");
				signInWithPhoneAuthCredential(credential); // credenziali inserite corrette, accesso
			}
			
			@Override
			public void onVerificationFailed(FirebaseException e) {
				MyLogger.log("Login verification failed:");
				if (e instanceof FirebaseAuthInvalidCredentialsException) {
					// Invalid request
					MyLogger.log("    invalid credentials");
				} else if (e instanceof FirebaseTooManyRequestsException) {
					// The SMS quota for the project has been exceeded
					MyLogger.log("    TO MANY REQUESTS!");
				}
			}
			
			@Override
			public void onCodeSent(@NonNull String verificationId, @NonNull PhoneAuthProvider.ForceResendingToken token) {
				mVerificationId = verificationId;
				mToken = token;
				codeSent = true;
				MyLogger.log("Login verification code sent");
			}
		};
	}
	
	private void initTimer(){
		timer = new CountDownTimer(codeInterval * 1000, 1000) {
			@Override
			public void onTick(long millisUntilFinished) {
				resendTimer.setText(getString(R.string.resend_code_in_x_seconds, millisUntilFinished / 1000));
			}
			
			@Override
			public void onFinish() {
				resendCode.setEnabled(true);
				resendTimer.setText(R.string.resend_code);
			}
		};
		timer.start();
	}
	
}
