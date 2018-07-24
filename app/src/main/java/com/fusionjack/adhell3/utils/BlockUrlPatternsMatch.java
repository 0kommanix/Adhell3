package com.fusionjack.adhell3.utils;

import com.fusionjack.adhell3.BuildConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BlockUrlPatternsMatch {

    private static final String WILDCARD_PATTERN = "(?im)(?=^[*]|.*[*]$)^(?:\\*[.-]?)?(?:(?!-)[\\w-]+(?:(?<!-)\\.)?)+(?:[a-z]+)(?:[.-]?\\*)?$";
    private static final Pattern wildcard_r = Pattern.compile(WILDCARD_PATTERN);

    private static final String DOMAIN_PATTERN = "(?im)(?=^.{4,253}$)(^((?!-)[a-z0-9-]{1,63}(?<!-)\\.)+[a-z]{2,63}$)";
    private static final Pattern domain_r = Pattern.compile(DOMAIN_PATTERN);

    // Define pattern for filter files: ||something.com^ or ||something.com^$third-party
    private static final String FILTER_PATTERN = "(?im)(?=.{4,253}\\^)((?<=^\\|\\|)(((?!-)[a-z0-9-]{1,63}(?<!-)\\.)+[a-z]{2,63})(?=\\^([$]third-party)?$))";
    private static final Pattern filter_r = Pattern.compile(FILTER_PATTERN);

    // Knox URL - Must contain a letter in prefix / domain
    private static final String KNOX_VALID_PATTERN = "(?i)(^(?=.*[a-z]).*$)";
    private static final Pattern knox_valid_r = Pattern.compile(KNOX_VALID_PATTERN);

    private static String domainPrefix = BuildConfig.DOMAIN_PREFIX.trim();
    private static final String WILDCARD_PREFIX = "*";

    private BlockUrlPatternsMatch() {
    }

    private static boolean wildcardValid (String domain) {
        return wildcard_r.matcher(domain).matches();
    }

    private static boolean domainValid (String domain){
        return domain_r.matcher(domain).matches();
    }

    private static String validHostFileDomains(String hostFileStr) {

        final Matcher filterPatternMatch = filter_r.matcher(hostFileStr);
        final Matcher domainPatternMatch = domain_r.matcher(hostFileStr);
        final Matcher wildcardPatternMatch = wildcard_r.matcher(hostFileStr);

        // Create a new string builder to hold our valid domains
        StringBuilder validDomainsStrBuilder = new StringBuilder();

        // Filter patterns
        while (filterPatternMatch.find()) {
            String filterListDomain = filterPatternMatch.group();
            List<String> domainsToAdd = new ArrayList<>(processPrefixingOptions(filterListDomain));

            for (String domain : domainsToAdd) {
                validDomainsStrBuilder.append(domain);
                validDomainsStrBuilder.append("\n");
            }
        }
        // Standard domains
        while(domainPatternMatch.find()) {
            String standardDomain = domainPatternMatch.group();
            List<String> domainsToAdd = new ArrayList<>(processPrefixingOptions(standardDomain));

            for (String domain : domainsToAdd) {
                validDomainsStrBuilder.append(domain);
                validDomainsStrBuilder.append("\n");
            }
        }
        // Wildcards
        while(wildcardPatternMatch.find()) {
            String wildcard = wildcardPatternMatch.group();
            validDomainsStrBuilder.append(wildcard);
            validDomainsStrBuilder.append("\n");
        }

        return validDomainsStrBuilder.toString();
    }

    public static boolean isUrlValid(String url) {
        if (url.contains("*")) {
            return BlockUrlPatternsMatch.wildcardValid(url);
        }
        return BlockUrlPatternsMatch.domainValid(url);
    }

    public static String getValidHostFileDomains(String hostFileStr) {
        return BlockUrlPatternsMatch.validHostFileDomains(hostFileStr);
    }

    private static List<String> processPrefixingOptions (String url) {
        List<String> urlsToAdd = new ArrayList<>();

        switch(domainPrefix) {
            case "*" :
                urlsToAdd.add(conditionallyPrefix(url));
                break;
            case "*." :
                urlsToAdd.add(getValidKnoxUrl(url));
                urlsToAdd.add(conditionallyPrefix(url));
                break;
            case "" :
                urlsToAdd.add(getValidKnoxUrl(url));
                break;
            default :
                urlsToAdd.add(getValidKnoxUrl(url));
                break;
        }
        return urlsToAdd;
    }

    private static String conditionallyPrefix(String url) {
        return (url.startsWith(domainPrefix) ? "" : domainPrefix) + url;
    }

    public static String getValidKnoxUrl(String url) {
        // Knox seems invalidate a domain if the prefix does not contain any letters.
        // We will programmatically prefix domains such as 123.test.com, but not t123.test.com

        // If the url is a wildcard, return it as is.
        if (url.contains(WILDCARD_PREFIX)) {
            return url;
        }

        // Grab the prefix
        String prefix = url.split("\\Q.\\E")[0];
        // Regex: must contain a letter (excl wildcards)
        final Matcher prefix_valid = knox_valid_r.matcher(prefix);

        // If we don't have any letters in the prefix
        // Add a wildcard prefix as a safety net
        return (prefix_valid.matches() ? "" : WILDCARD_PREFIX) + url;
    }

}