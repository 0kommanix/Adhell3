package com.fusionjack.adhell3.fragments;

import android.app.Activity;
import android.app.ProgressDialog;
import android.app.enterprise.ApplicationPermissionControlPolicy;
import android.app.enterprise.ApplicationPolicy;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.fusionjack.adhell3.R;
import com.fusionjack.adhell3.blocker.ContentBlocker;
import com.fusionjack.adhell3.blocker.ContentBlocker56;
import com.fusionjack.adhell3.blocker.ContentBlocker57;
import com.fusionjack.adhell3.db.AppDatabase;
import com.fusionjack.adhell3.db.DatabaseFactory;
import com.fusionjack.adhell3.db.entity.AppPermission;
import com.fusionjack.adhell3.db.entity.BlockUrl;
import com.fusionjack.adhell3.db.entity.BlockUrlProvider;
import com.fusionjack.adhell3.db.entity.DisabledPackage;
import com.fusionjack.adhell3.dialogfragment.FirewallDialogFragment;
import com.fusionjack.adhell3.utils.AdhellFactory;
import com.fusionjack.adhell3.utils.BlockUrlUtils;
import com.fusionjack.adhell3.utils.DeviceAdminInteractor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class BlockerFragment extends Fragment {
    private static final String TAG = BlockerFragment.class.getCanonicalName();

    private FragmentManager fragmentManager;
    private AppCompatActivity parentActivity;
    private Button mPolicyChangeButton;
    private Button reportButton;
    private TextView isSupportedTextView;
    private ContentBlocker contentBlocker;

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.settings, menu);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fragmentManager = getActivity().getSupportFragmentManager();
        parentActivity = (AppCompatActivity) getActivity();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_app_settings:
                Log.d(TAG, "App setting action clicked");
                fragmentManager
                        .beginTransaction()
                        .replace(R.id.fragmentContainer, new AppSettingsFragment(), AppSettingsFragment.class.getCanonicalName())
                        .addToBackStack(AppSettingsFragment.class.getCanonicalName())
                        .commit();
                return true;
            case R.id.backup_database:
                new BackupDatabaseAsyncTask(getActivity()).execute();
                break;
            case R.id.restore_database:
                new RestoreDatabaseAsyncTask(this, getActivity()).execute();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (parentActivity.getSupportActionBar() != null) {
            parentActivity.getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            parentActivity.getSupportActionBar().setHomeButtonEnabled(false);
        }
        getActivity().setTitle(getString(R.string.blocker_fragment_title));
        setHasOptionsMenu(true);

        View view = inflater.inflate(R.layout.fragment_blocker, container, false);
        mPolicyChangeButton = view.findViewById(R.id.policyChangeButton);
        isSupportedTextView = view.findViewById(R.id.isSupportedTextView);
        reportButton = view.findViewById(R.id.adhellReportsButton);
        TextView warningMessageTextView = view.findViewById(R.id.warningMessageTextView);

        contentBlocker = DeviceAdminInteractor.getInstance().getContentBlocker();
        if (!(contentBlocker instanceof ContentBlocker56 || contentBlocker instanceof ContentBlocker57)) {
            warningMessageTextView.setVisibility(View.VISIBLE);
        } else {
            warningMessageTextView.setVisibility(View.GONE);
        }

        if (contentBlocker != null && contentBlocker.isEnabled()) {
            mPolicyChangeButton.setText(R.string.block_button_text_turn_off);
            isSupportedTextView.setText(R.string.block_enabled);
        } else {
            mPolicyChangeButton.setText(R.string.block_button_text_turn_on);
            isSupportedTextView.setText(R.string.block_disabled);
        }

        mPolicyChangeButton.setOnClickListener(v -> {
            Log.d(TAG, "Adhell switch button has been clicked");
            new SetFirewallAsyncTask(this, fragmentManager).execute();
        });

        if (contentBlocker.isEnabled() &&
                (contentBlocker instanceof ContentBlocker56 || contentBlocker instanceof ContentBlocker57)) {
            reportButton.setOnClickListener(view1 -> {
                FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
                fragmentTransaction.replace(R.id.fragmentContainer, new AdhellReportsFragment());
                fragmentTransaction.addToBackStack("main_to_reports");
                fragmentTransaction.commit();
            });
        } else {
            reportButton.setVisibility(View.GONE);
        }
        return view;
    }

    private void updateUserInterface() {
        if (contentBlocker.isEnabled()) {
            mPolicyChangeButton.setText(R.string.block_button_text_turn_off);
            isSupportedTextView.setText(R.string.block_enabled);
        } else {
            mPolicyChangeButton.setText(R.string.block_button_text_turn_on);
            isSupportedTextView.setText(R.string.block_disabled);
        }

        if (contentBlocker.isEnabled() &&
                (contentBlocker instanceof ContentBlocker56 || contentBlocker instanceof ContentBlocker57)) {
            reportButton.setVisibility(View.VISIBLE);
            reportButton.setOnClickListener(view1 -> {
                FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
                fragmentTransaction.replace(R.id.fragmentContainer, new AdhellReportsFragment());
                fragmentTransaction.addToBackStack("main_to_reports");
                fragmentTransaction.commit();
            });
        } else {
            reportButton.setVisibility(View.GONE);
        }
    }

    private static class SetFirewallAsyncTask extends AsyncTask<Void, Void, Void> {
        private FragmentManager fragmentManager;
        private FirewallDialogFragment fragment;
        private BlockerFragment parentFragment;
        private ContentBlocker contentBlocker;
        private Handler handler;

        SetFirewallAsyncTask(BlockerFragment parentFragment, FragmentManager fragmentManager) {
            this.parentFragment = parentFragment;
            this.fragmentManager = fragmentManager;
            this.contentBlocker = DeviceAdminInteractor.getInstance().getContentBlocker();

            this.handler = new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    fragment.appendText(msg.obj.toString());
                }
            };
        }

        @Override
        protected void onPreExecute() {
            if (contentBlocker.isEnabled()) {
                fragment = FirewallDialogFragment.newInstance("Disabling Adhell...");
            } else {
                fragment = FirewallDialogFragment.newInstance("Enabling Adhell...");
            }
            fragment.setCancelable(false);
            fragment.show(fragmentManager, "dialog_firewall");
        }

        @Override
        protected Void doInBackground(Void... args) {
            contentBlocker.setHandler(handler);
            if (contentBlocker.isEnabled()) {
                contentBlocker.disableBlocker();
            } else {
                contentBlocker.enableBlocker();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            fragment.enableCloseButton();
            parentFragment.updateUserInterface();
        }
    }

    private static class BackupDatabaseAsyncTask extends AsyncTask<Void, Void, String> {
        private ProgressDialog dialog;
        private AlertDialog.Builder builder;

        BackupDatabaseAsyncTask(Activity activity) {
            dialog = new ProgressDialog(activity);
            builder = new AlertDialog.Builder(activity);
        }

        @Override
        protected void onPreExecute() {
            dialog.setMessage("Backup database is running...");
            dialog.show();
        }

        @Override
        protected String doInBackground(Void... args) {
            try {
                DatabaseFactory.getInstance().backupDatabase();
                return null;
            } catch (Exception e) {
                return e.getMessage();
            }
        }

        @Override
        protected void onPostExecute(String message) {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }

            if (message == null) {
                builder.setMessage("Backup database is finished");
                builder.setTitle("Info");
            } else {
                builder.setMessage(message);
                builder.setTitle("Error");
            }
            builder.create().show();
        }
    }

    private static class RestoreDatabaseAsyncTask extends AsyncTask<Void, String, String> {
        private ProgressDialog dialog;
        private AlertDialog.Builder builder;
        private BlockerFragment fragment;
        private AppDatabase appDatabase;
        private ApplicationPolicy appPolicy;
        private ApplicationPermissionControlPolicy appControlPolicy;

        RestoreDatabaseAsyncTask(BlockerFragment fragment, Activity activity) {
            this.fragment = fragment;
            this.builder = new AlertDialog.Builder(activity);
            this.dialog = new ProgressDialog(activity);
            this.appDatabase = AdhellFactory.getInstance().getAppDatabase();
            this.appPolicy = AdhellFactory.getInstance().getAppPolicy();
            this.appControlPolicy = AdhellFactory.getInstance().getAppControlPolicy();
        }

        @Override
        protected void onPreExecute() {
            dialog.setMessage("Restore database is running...");
            dialog.show();
        }

        @Override
        protected String doInBackground(Void... args) {
            try {
                DatabaseFactory.getInstance().restoreDatabase();

                publishProgress("Updating all providers...");

                List<BlockUrlProvider> providers = appDatabase.blockUrlProviderDao().getAll2();
                appDatabase.blockUrlDao().deleteAll();
                for (BlockUrlProvider provider : providers) {
                    try {
                        List<BlockUrl> blockUrls = BlockUrlUtils.loadBlockUrls(provider);
                        provider.count = blockUrls.size();
                        provider.lastUpdated = new Date();
                        appDatabase.blockUrlProviderDao().updateBlockUrlProviders(provider);
                        appDatabase.blockUrlDao().insertAll(blockUrls);
                    } catch (Exception e) {
                        Log.e(TAG, e.getMessage(), e);
                    }
                }

                publishProgress("Disabling apps...");
                List<DisabledPackage> disabledPackages = appDatabase.disabledPackageDao().getAll();
                for (DisabledPackage disabledPackage : disabledPackages) {
                    appPolicy.setDisableApplication(disabledPackage.packageName);
                }

                publishProgress("Disabling app's permissions...");
                List<AppPermission> appPermissions = appDatabase.appPermissionDao().getAll();
                for (AppPermission appPermission : appPermissions) {
                    List<String> packageList = new ArrayList<>();
                    packageList.add(appPermission.packageName);
                    appControlPolicy.addPackagesToPermissionBlackList(appPermission.permissionName, packageList);
                }

                return null;
            } catch (Exception e) {
                return e.getMessage();
            }
        }

        @Override
        protected void onProgressUpdate(String... values) {
            dialog.setMessage(values[0]);
        }

        @Override
        protected void onPostExecute(String message) {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }

            fragment.updateUserInterface();
            if (message == null) {
                builder.setMessage("Restore database is finished. Turn on Adhell.");
                builder.setTitle("Info");
            } else {
                builder.setMessage(message);
                builder.setTitle("Error");
            }
            builder.create().show();
        }
    }
}
