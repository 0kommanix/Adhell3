package com.fusionjack.adhell3.blocker;

import android.os.Handler;

import com.fusionjack.adhell3.db.AppDatabase;
import com.fusionjack.adhell3.db.entity.AppInfo;
import com.fusionjack.adhell3.db.entity.UserBlockUrl;
import com.fusionjack.adhell3.db.entity.WhiteUrl;
import com.fusionjack.adhell3.utils.AdhellFactory;
import com.fusionjack.adhell3.utils.BlockUrlPatternsMatch;
import com.fusionjack.adhell3.utils.BlockUrlUtils;
import com.fusionjack.adhell3.utils.LogUtils;
import com.sec.enterprise.AppIdentity;
import com.sec.enterprise.firewall.DomainFilterRule;
import com.sec.enterprise.firewall.Firewall;
import com.sec.enterprise.firewall.FirewallResponse;
import com.sec.enterprise.firewall.FirewallRule;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

public class ContentBlocker56 implements ContentBlocker {
    private static ContentBlocker56 mInstance = null;

    private Firewall firewall;
    private AppDatabase appDatabase;

    private Handler handler;

    private ContentBlocker56() {
        this.appDatabase = AdhellFactory.getInstance().getAppDatabase();
        this.firewall = AdhellFactory.getInstance().getFirewall();
    }

    public static ContentBlocker56 getInstance() {
        if (mInstance == null) {
            mInstance = getSync();
        }
        return mInstance;
    }

    private static synchronized ContentBlocker56 getSync() {
        if (mInstance == null) {
            mInstance = new ContentBlocker56();
        }
        return mInstance;
    }

    @Override
    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    @Override
    public boolean enableBlocker() {
        if (firewall == null) {
            return false;
        }

        try {
            processCustomRules();
            processMobileRestrictedApps();
            processWhitelistedApps();
            processWhitelistedDomains();
            processBlockedDomains();

            if (!firewall.isFirewallEnabled()) {
                LogUtils.getInstance().writeInfo("\nEnabling firewall...", handler);
                firewall.enableFirewall(true);
            }
            if (!firewall.isDomainFilterReportEnabled()) {
                LogUtils.getInstance().writeInfo("Enabling firewall report...", handler);
                firewall.enableDomainFilterReport(true);
            }
            LogUtils.getInstance().writeInfo("Firewall is enabled.", handler);
        } catch (Exception e) {
            disableBlocker();

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String sStackTrace = sw.toString();

            String errorMessage = "\nFailed to enable firewall: " + e.getMessage() + "\n\nStack trace:\n" + sStackTrace;
            LogUtils.getInstance().writeError(errorMessage, e, handler);

            return false;
        } finally {
            LogUtils.getInstance().close();
        }

        return true;
    }

    private void processCustomRules() throws Exception {
        LogUtils.getInstance().writeInfo("\nProcessing custom rules...", handler);

        int count = 0;
        List<UserBlockUrl> userBlockUrls = appDatabase.userBlockUrlDao().getAll2();
        for (UserBlockUrl userBlockUrl : userBlockUrls) {
            if (userBlockUrl.url.indexOf('|') != -1) {
                StringTokenizer tokens = new StringTokenizer(userBlockUrl.url, "|");
                if (tokens.countTokens() == 3) {
                    String packageName = tokens.nextToken();
                    String ipAddress = tokens.nextToken();
                    String port = tokens.nextToken();

                    // Define firewall rule
                    FirewallRule[] firewallRules = new FirewallRule[1];
                    firewallRules[0] = new FirewallRule(FirewallRule.RuleType.DENY, Firewall.AddressType.IPV4);
                    firewallRules[0].setIpAddress(ipAddress);
                    firewallRules[0].setPortNumber(port);
                    firewallRules[0].setApplication(new AppIdentity(packageName, null));

                    addFirewallRules(firewallRules);
                }
            }
        }

        LogUtils.getInstance().writeInfo("Custom rule size: " + count, handler);
    }

    private void processMobileRestrictedApps() throws Exception {
        LogUtils.getInstance().writeInfo("\nProcessing mobile restricted apps...", handler);

        List<AppInfo> restrictedApps = appDatabase.applicationInfoDao().getMobileRestrictedApps();
        LogUtils.getInstance().writeInfo("Restricted apps size: " + restrictedApps.size(), handler);
        if (restrictedApps.size() == 0) {
            return;
        }

        // Define DENY rules for mobile data
        FirewallRule[] mobileRules = new FirewallRule[restrictedApps.size()];
        for (int i = 0; i < restrictedApps.size(); i++) {
            mobileRules[i] = new FirewallRule(FirewallRule.RuleType.DENY, Firewall.AddressType.IPV4);
            mobileRules[i].setNetworkInterface(Firewall.NetworkInterface.MOBILE_DATA_ONLY);
            mobileRules[i].setApplication(new AppIdentity(restrictedApps.get(i).packageName, null));
        }

        addFirewallRules(mobileRules);
    }

    private void processWhitelistedApps() throws Exception {
        LogUtils.getInstance().writeInfo("\nProcessing white-listed apps...", handler);

        // Create domain filter rule for white listed apps
        List<AppInfo> whitelistedApps = appDatabase.applicationInfoDao().getWhitelistedApps();
        LogUtils.getInstance().writeInfo("Whitelisted apps size: " + whitelistedApps.size(), handler);
        if (whitelistedApps.size() == 0) {
            return;
        }

        List<DomainFilterRule> rules = new ArrayList<>();
        List<String> superAllow = new ArrayList<>();
        superAllow.add("*");
        for (AppInfo app : whitelistedApps) {
            LogUtils.getInstance().writeInfo("Whitelisted app: " + app.packageName, handler);
            rules.add(new DomainFilterRule(new AppIdentity(app.packageName, null), new ArrayList<>(), superAllow));
        }
        addDomainFilterRules(rules);
    }

    private void processWhitelistedDomains() throws Exception {
        LogUtils.getInstance().writeInfo("\nProcessing white-listed domains...", handler);

        // Process user-defined white list
        // 1. URL for all packages: url
        // 2. URL for individual package: packageName|url
        List<WhiteUrl> whiteUrls = appDatabase.whiteUrlDao().getAll2();
        LogUtils.getInstance().writeInfo("User whitelisted URL size: " + whiteUrls.size(), handler);
        if (whiteUrls.size() == 0) {
            return;
        }

        Set<String> denyList = BlockUrlUtils.getUniqueBlockedUrls(appDatabase, handler, false);
        for (WhiteUrl whiteUrl : whiteUrls) {
            if (whiteUrl.url.indexOf('|') != -1) {
                StringTokenizer tokens = new StringTokenizer(whiteUrl.url, "|");
                if (tokens.countTokens() == 2) {
                    final String packageName = tokens.nextToken();
                    final String url = tokens.nextToken();
                    final AppIdentity appIdentity = new AppIdentity(packageName, null);
                    LogUtils.getInstance().writeInfo("PackageName: " + packageName + ", WhiteUrl: " + url, handler);

                    List<String> whiteList = new ArrayList<>();
                    whiteList.add(url);

                    List<DomainFilterRule> rules = new ArrayList<>();
                    rules.add(new DomainFilterRule(appIdentity, new ArrayList<>(denyList), whiteList));
                    addDomainFilterRules(rules);
                }
            }
        }

        // Whitelist URL for all apps
        Set<String> allowList = new HashSet<>();
        for (WhiteUrl whiteUrl : whiteUrls) {
            if (whiteUrl.url.indexOf('|') == -1) {
                final String url = BlockUrlPatternsMatch.getValidatedUrl(whiteUrl.url);
                allowList.add(url);
                LogUtils.getInstance().writeInfo("WhiteUrl: " + url, handler);
            }
        }
        if (allowList.size() > 0) {
            final AppIdentity appIdentity = new AppIdentity("*", null);
            List<DomainFilterRule> rules = new ArrayList<>();
            rules.add(new DomainFilterRule(appIdentity, new ArrayList<>(), new ArrayList<>(allowList)));
            addDomainFilterRules(rules);
        }
    }

    private void processBlockedDomains() throws Exception {
        LogUtils.getInstance().writeInfo("\nProcessing blocked domains...", handler);

        Set<String> denyList = BlockUrlUtils.getUniqueBlockedUrls(appDatabase, handler, true);
        List<DomainFilterRule> rules = new ArrayList<>();
        AppIdentity appIdentity = new AppIdentity("*", null);
        rules.add(new DomainFilterRule(appIdentity, new ArrayList<>(denyList), new ArrayList<>()));
        addDomainFilterRules(rules);
    }

    @Override
    public boolean disableBlocker() {
        if (firewall == null) {
            return false;
        }

        FirewallResponse[] response;
        try {
            // Clear IP rules
            response = firewall.clearRules(Firewall.FIREWALL_ALL_RULES);
            LogUtils.getInstance().writeInfo(response == null ? "\nNo response" : "\n" + response[0].getMessage(), handler);

            // Clear domain filter rules
            response = firewall.removeDomainFilterRules(DomainFilterRule.CLEAR_ALL);
            LogUtils.getInstance().writeInfo(response == null ? "No response" : response[0].getMessage(), handler);

            if (firewall.isFirewallEnabled()) {
                firewall.enableFirewall(false);
            }
            if (firewall.isDomainFilterReportEnabled()) {
                firewall.enableDomainFilterReport(false);
            }

            LogUtils.getInstance().writeInfo("Firewall is disabled.", handler);
        } catch (SecurityException ex) {
            LogUtils.getInstance().writeError("Failed to disable firewall: " + ex.getMessage(), ex, handler);
            return false;
        } finally {
            LogUtils.getInstance().close();
        }

        return true;
    }

    @Override
    public boolean isEnabled() {
        return firewall != null && firewall.isFirewallEnabled();
    }

    private void addDomainFilterRules(List<DomainFilterRule> domainRules) throws Exception {
        if (firewall == null) {
            throw new Exception("Knox Firewall is not initialized");
        }

        LogUtils.getInstance().writeInfo("Adding domain filter rule to Knox Firewall...", handler);
        FirewallResponse[] response;
        try {
            response = firewall.addDomainFilterRules(domainRules);
            if (response == null) {
                Exception ex = new Exception("There was no response from Knox Firewall");
                LogUtils.getInstance().writeError("There was no response from Knox Firewall", ex, handler);
                throw ex;
            } else {
                LogUtils.getInstance().writeInfo("Result: " + response[0].getMessage(), handler);
                if (FirewallResponse.Result.SUCCESS != response[0].getResult()) {
                    Exception ex = new Exception(response[0].getMessage());
                    LogUtils.getInstance().writeError(response[0].getMessage(), ex, handler);
                    throw ex;
                }
            }
        } catch (SecurityException ex) {
            // Missing required MDM permission
            LogUtils.getInstance().writeError("Failed to add domain filter rule to Knox Firewall", ex, handler);
        }
    }

    private void addFirewallRules(FirewallRule[] firewallRules) throws Exception {
        if (firewall == null) {
            throw new Exception("Knox Firewall is not initialized");
        }

        LogUtils.getInstance().writeInfo("Adding firewall rule to Knox Firewall...", handler);
        FirewallResponse[] response;
        try {
            response = firewall.addRules(firewallRules);
            if (response == null) {
                Exception ex = new Exception("There was no response from Knox Firewall");
                LogUtils.getInstance().writeError("There was no response from Knox Firewall", ex, handler);
                throw ex;
            } else {
                LogUtils.getInstance().writeInfo("Result: " + response[0].getMessage(), handler);
                if (FirewallResponse.Result.SUCCESS != response[0].getResult()) {
                    Exception ex = new Exception(response[0].getMessage());
                    LogUtils.getInstance().writeError(response[0].getMessage(), ex, handler);
                    throw ex;
                }
            }
        } catch (SecurityException ex) {
            // Missing required MDM permission
            LogUtils.getInstance().writeError("Failed to add firewall rules to Knox Firewall", ex, handler);
        }
    }
}