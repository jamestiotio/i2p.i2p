package net.i2p.router.peermanager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;

/**
 *  Write profiles to disk at shutdown,
 *  read at startup.
 *  The files are gzip compressed, we previously stored them
 *  with a ".dat" extension instead of ".txt.gz", so it wasn't apparent.
 *  Now migrated to a ".txt.gz" extension.
 */
class ProfilePersistenceHelper {
    private final Log _log;
    private final RouterContext _context;
    
    public final static String PROP_PEER_PROFILE_DIR = "router.profileDir";
    public final static String DEFAULT_PEER_PROFILE_DIR = "peerProfiles";
    private final static String NL = System.getProperty("line.separator");
    private static final String PREFIX = "profile-";
    private static final String SUFFIX = ".txt.gz";
    private static final String UNCOMPRESSED_SUFFIX = ".txt";
    private static final String OLD_SUFFIX = ".dat";
    private static final int MIN_NAME_LENGTH = PREFIX.length() + 44 + OLD_SUFFIX.length();
    private static final String DIR_PREFIX = "p";
    private static final String B64 = Base64.ALPHABET_I2P;
    // Max to read in at startup
    private static final int LIMIT_PROFILES = SystemVersion.isSlow() ? 1000 : 4000;
    
    private final File _profileDir;
    private Hash _us;
    
    public ProfilePersistenceHelper(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(ProfilePersistenceHelper.class);
        String dir = _context.getProperty(PROP_PEER_PROFILE_DIR, DEFAULT_PEER_PROFILE_DIR);
        _profileDir = new SecureDirectory(_context.getRouterDir(), dir);
        if (!_profileDir.exists())
            _profileDir.mkdirs();
        for (int j = 0; j < B64.length(); j++) {
            File subdir = new SecureDirectory(_profileDir, DIR_PREFIX + B64.charAt(j));
            if (!subdir.exists())
                subdir.mkdir();
        }
    }
    
    public void setUs(Hash routerIdentHash) { _us = routerIdentHash; }
    
    /**
     * write out the data from the profile to the file
     * @return success
     */
    public boolean writeProfile(PeerProfile profile) {
        File f = pickFile(profile);
        OutputStream fos = null;
        try {
            fos = new BufferedOutputStream(new GZIPOutputStream(new SecureFileOutputStream(f)));
            writeProfile(profile, fos, false);
        } catch (IOException ioe) {
            _log.error("Error writing profile to " + f);
            return false;
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
        return true;
    }

    /**
     * write out the data from the profile to the stream
     * includes comments
     */
    public void writeProfile(PeerProfile profile, OutputStream out) throws IOException {
        writeProfile(profile, out, true);
    }

    /**
     * write out the data from the profile to the stream
     * @param addComments add comment lines to the output
     * @since 0.9.41
     */
    @SuppressWarnings("deprecation")
    public void writeProfile(PeerProfile profile, OutputStream out, boolean addComments) throws IOException {
        String groups = null;
        if (_context.profileOrganizer().isFailing(profile.getPeer())) {
            groups = "Failing";
        } else if (!_context.profileOrganizer().isHighCapacity(profile.getPeer())) {
            groups = "Standard";
        } else {
            if (_context.profileOrganizer().isFast(profile.getPeer()))
                groups = "Fast, High Capacity";
            else
                groups = "High Capacity";
            
            if (_context.profileOrganizer().isWellIntegrated(profile.getPeer()))
                groups = groups + ", Integrated";
        }
        
        StringBuilder buf = new StringBuilder(512);
        if (addComments) {
            buf.append("########################################################################").append(NL);
            buf.append("# Profile for peer ").append(profile.getPeer().toBase64()).append(NL);
            if (_us != null)
                buf.append("# as calculated by ").append(_us.toBase64()).append(NL);
            buf.append("#").append(NL);
            buf.append("# Speed: ").append(profile.getSpeedValue()).append(NL);
            buf.append("# Capacity: ").append(profile.getCapacityValue()).append(NL);
            buf.append("# Integration: ").append(profile.getIntegrationValue()).append(NL);
            buf.append("# Groups: ").append(groups).append(NL);
            buf.append("#").append(NL);
            buf.append("########################################################################").append(NL);
            buf.append("##").append(NL);
        }
        add(buf, addComments, "speedBonus", profile.getSpeedBonus(), "Manual adjustment to the speed score");
        add(buf, addComments, "capacityBonus", profile.getCapacityBonus(), "Manual adjustment to the capacity score");
        add(buf, addComments, "integrationBonus", profile.getIntegrationBonus(), "Manual adjustment to the integration score");
        addDate(buf, addComments, "firstHeardAbout", profile.getFirstHeardAbout(), "When did we first get a reference to this peer?");
        addDate(buf, addComments, "lastHeardAbout", profile.getLastHeardAbout(), "When did we last get a reference to this peer?");
        addDate(buf, addComments, "lastHeardFrom", profile.getLastHeardFrom(), "When did we last get a message from the peer?");
        addDate(buf, addComments, "lastSentToSuccessfully", profile.getLastSendSuccessful(), "When did we last send the peer a message successfully?");
        addDate(buf, addComments, "lastFailedSend", profile.getLastSendFailed(), "When did we last fail to send a message to the peer?");
        if (PeerProfile.ENABLE_TUNNEL_TEST_RESPONSE_TIME)
            add(buf, addComments, "tunnelTestTimeAverage", profile.getTunnelTestTimeAverage(), "Moving average as to how fast the peer replies");
        add(buf, addComments, "tunnelPeakThroughput", profile.getPeakThroughputKBps(), "KBytes/sec");
        add(buf, addComments, "tunnelPeakTunnelThroughput", profile.getPeakTunnelThroughputKBps(), "KBytes/sec");
        add(buf, addComments, "tunnelPeakTunnel1mThroughput", profile.getPeakTunnel1mThroughputKBps(), "KBytes/sec");
        if (addComments)
            buf.append(NL);
        
        out.write(buf.toString().getBytes("UTF-8"));
        
        if (profile.getIsExpanded()) {
            // only write out expanded data if, uh, we've got it
            profile.getTunnelHistory().store(out, addComments);
            //profile.getReceiveSize().store(out, "receiveSize");
            //profile.getSendSuccessSize().store(out, "sendSuccessSize");
            profile.getTunnelCreateResponseTime().store(out, "tunnelCreateResponseTime", addComments);
            if (PeerProfile.ENABLE_TUNNEL_TEST_RESPONSE_TIME)
                profile.getTunnelTestResponseTime().store(out, "tunnelTestResponseTime", addComments);
        }

        if (profile.getIsExpandedDB()) {
            profile.getDBHistory().store(out, addComments);
            profile.getDbIntroduction().store(out, "dbIntroduction", addComments);
            profile.getDbResponseTime().store(out, "dbResponseTime", addComments);
        }
    }
    
    /** @since 0.8.5 */
    private static void addDate(StringBuilder buf, boolean addComments, String name, long val, String description) {
        if (addComments) {
            String when = val > 0 ? (new Date(val)).toString() : "Never";
            add(buf, true, name, val, description + ' ' + when);
        } else {
            add(buf, false, name, val, description);
        }
    }
    
    /** @since 0.8.5 */
    private static void add(StringBuilder buf, boolean addComments, String name, long val, String description) {
        if (addComments)
            buf.append("# ").append(name).append(NL).append("# ").append(description).append(NL);
        buf.append(name).append('=').append(val).append(NL);
        if (addComments)
            buf.append(NL);
    }
    
    /** @since 0.8.5 */
    private static void add(StringBuilder buf, boolean addComments, String name, float val, String description) {
        if (addComments)
            buf.append("# ").append(name).append(NL).append("# ").append(description).append(NL);
        buf.append(name).append('=').append(val).append(NL);
        if (addComments)
            buf.append(NL);
    }
    
    public List<PeerProfile> readProfiles() {
        long start = System.currentTimeMillis();
        long down = _context.router().getEstimatedDowntime();
        long cutoff = down < 15*24*60*60*1000L ? start - down - 24*60*60*1000 : start;
        List<File> files = selectFiles();
        if (files.size() > LIMIT_PROFILES)
            Collections.shuffle(files, _context.random());
        List<PeerProfile> profiles = new ArrayList<PeerProfile>(Math.min(LIMIT_PROFILES, files.size()));
        int count = 0;
        for (File f :  files) {
            if (count >= LIMIT_PROFILES) {
                f.delete();
                continue;
            }
            PeerProfile profile = readProfile(f, cutoff);
            if (profile != null) {
                profiles.add(profile);
                count++;
            }
        }
        long duration = System.currentTimeMillis() - start;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Loading " + count + " profiles took " + duration + "ms");
        return profiles;
    }
    
    private static class ProfileFilter implements FilenameFilter {
        public boolean accept(File dir, String filename) {
            return (filename.startsWith(PREFIX) &&
                    filename.length() >= MIN_NAME_LENGTH &&
                    (filename.endsWith(SUFFIX) || filename.endsWith(OLD_SUFFIX) || filename.endsWith(UNCOMPRESSED_SUFFIX)));
        }
    }

    private List<File> selectFiles() {
        FilenameFilter filter = new ProfileFilter();
        File files[] = _profileDir.listFiles(filter);
        if (files != null && files.length > 0)
            migrate(files);
        List<File> rv = new ArrayList<File>(1024);
        for (int j = 0; j < B64.length(); j++) {
            File subdir = new File(_profileDir, DIR_PREFIX + B64.charAt(j));
            files = subdir.listFiles(filter);
            if (files == null)
                continue;
            for (int i = 0; i < files.length; i++)
                rv.add(files[i]);
        }
        return rv;
    }

    /**
     *  Migrate from one-level to two-level directory structure
     *  @since 0.9.4
     */
    private void migrate(File[] files) {
        for (int i = 0; i < files.length; i++) {
            File from = files[i];
            if (!from.isFile())
                continue;
            File dir = new File(_profileDir, DIR_PREFIX + from.getName().charAt(PREFIX.length()));
            File to = new File(dir, from.getName());
            FileUtil.rename(from, to);
        }
    }
    
    /**
     *  Delete profile files with timestamps older than 'age' ago
     *  @return number deleted
     *  @since 0.9.28
     */
    public int deleteOldProfiles(long age) {
        long cutoff = System.currentTimeMillis() - age;
        List<File> files = selectFiles();
        int i = 0;
        for (File f :  files) {
            if (!f.isFile())
                continue;
            if (f.lastModified() < cutoff) {
                i++;
                f.delete();
            }
        }
        if (_log.shouldWarn())
            _log.warn("Deleted " + i + " old profiles");
        return i;
    }

    /**
     *  @param cutoff delete and return null if older than this (absolute time)
     */
    @SuppressWarnings("deprecation")
    public PeerProfile readProfile(File file, long cutoff) {
        if (file.lastModified() < cutoff) {
            file.delete();
            return null;
        }
        Hash peer = getHash(file.getName());
        try {
            if (peer == null) {
                _log.error("The file " + file.getName() + " is not a valid hash");
                return null;
            }
            PeerProfile profile = new PeerProfile(_context, peer);
            Properties props = new Properties();
            
            loadProps(props, file);
            
            long lastSentToSuccessfully = getLong(props, "lastSentToSuccessfully");
            if (lastSentToSuccessfully <= cutoff) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Dropping old profile " + file.getName() + 
                              ", since we haven't heard from them in a long time");
                file.delete();
                return null;
            } else if (file.getName().endsWith(OLD_SUFFIX)) {
                // migrate to new file name, ignore failure
                String newName = file.getAbsolutePath();
                newName = newName.substring(0, newName.length() - OLD_SUFFIX.length()) + SUFFIX;
                boolean success = file.renameTo(new File(newName));
                if (!success)
                    // new file exists and on Windows?
                    file.delete();
            }
            
            profile.setCapacityBonus((int) getLong(props, "capacityBonus"));
            profile.setIntegrationBonus((int) getLong(props, "integrationBonus"));
            profile.setSpeedBonus((int) getLong(props, "speedBonus"));
            
            profile.setLastHeardAbout(getLong(props, "lastHeardAbout"));
            profile.setFirstHeardAbout(getLong(props, "firstHeardAbout"));
            profile.setLastSendSuccessful(getLong(props, "lastSentToSuccessfully"));
            profile.setLastSendFailed(getLong(props, "lastFailedSend"));
            profile.setLastHeardFrom(getLong(props, "lastHeardFrom"));

            if (PeerProfile.ENABLE_TUNNEL_TEST_RESPONSE_TIME)
                profile.setTunnelTestTimeAverage(getFloat(props, "tunnelTestTimeAverage"));

            profile.setPeakThroughputKBps(getFloat(props, "tunnelPeakThroughput"));
            profile.setPeakTunnelThroughputKBps(getFloat(props, "tunnelPeakTunnelThroughput"));
            profile.setPeakTunnel1mThroughputKBps(getFloat(props, "tunnelPeakTunnel1mThroughput"));
            
            profile.getTunnelHistory().load(props);

            // In the interest of keeping the in-memory profiles small,
            // don't load the DB info at all unless there is something interesting there
            // (i.e. floodfills)
            if (getLong(props, "dbHistory.lastLookupSuccessful") > 0 ||
                getLong(props, "dbHistory.lastLookupFailed") > 0 ||
                getLong(props, "dbHistory.lastStoreSuccessful") > 0 ||
                getLong(props, "dbHistory.lastStoreFailed") > 0) {
                profile.expandDBProfile();
                profile.getDBHistory().load(props);
                profile.getDbIntroduction().load(props, "dbIntroduction", true);
                profile.getDbResponseTime().load(props, "dbResponseTime", true);
            }

            //profile.getReceiveSize().load(props, "receiveSize", true);
            //profile.getSendSuccessSize().load(props, "sendSuccessSize", true);
            profile.getTunnelCreateResponseTime().load(props, "tunnelCreateResponseTime", true);

            if (PeerProfile.ENABLE_TUNNEL_TEST_RESPONSE_TIME)
                profile.getTunnelTestResponseTime().load(props, "tunnelTestResponseTime", true);
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Loaded the profile for " + peer.toBase64() + " from " + file.getName());
            
            fixupFirstHeardAbout(profile);
            return profile;
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error loading properties from " + file.getAbsolutePath(), e);
            file.delete();
            return null;
        }
    }

    /**
     *  First heard about wasn't always set correctly before,
     *  set it to the minimum of all recorded timestamps.
     *
     *  @since 0.9.24
     */
    private void fixupFirstHeardAbout(PeerProfile p) {
        long min = Long.MAX_VALUE;
        long t = p.getLastHeardAbout();
        if (t > 0 && t < min) min = t;
        t = p.getLastSendSuccessful();
        if (t > 0 && t < min) min = t;
        t = p.getLastSendFailed();
        if (t > 0 && t < min) min = t;
        t = p.getLastHeardFrom();
        if (t > 0 && t < min) min = t;
        // the first was never used and the last 4 were never persisted
        //DBHistory dh = p.getDBHistory();
        //if (dh != null) {
        //    t = dh.getLastLookupReceived();
        //    if (t > 0 && t < min) min = t;
        //    t = dh.getLastLookupSuccessful();
        //    if (t > 0 && t < min) min = t;
        //    t = dh.getLastLookupFailed();
        //    if (t > 0 && t < min) min = t;
        //    t = dh.getLastStoreSuccessful();
        //    if (t > 0 && t < min) min = t;
        //    t = dh.getLastStoreFailed();
        //    if (t > 0 && t < min) min = t;
        //}
        TunnelHistory th = p.getTunnelHistory();
        if (th != null) {
            t = th.getLastAgreedTo();
            if (t > 0 && t < min) min = t;
            t = th.getLastRejectedCritical();
            if (t > 0 && t < min) min = t;
            t = th.getLastRejectedBandwidth();
            if (t > 0 && t < min) min = t;
            t = th.getLastRejectedTransient();
            if (t > 0 && t < min) min = t;
            t = th.getLastRejectedProbabalistic();
            if (t > 0 && t < min) min = t;
            t = th.getLastFailed();
            if (t > 0 && t < min) min = t;
        }
        long fha = p.getFirstHeardAbout();
        if (min > 0 && min < Long.MAX_VALUE && (fha <= 0 || min < fha)) {
            p.setFirstHeardAbout(min);
            if (_log.shouldDebug())
                _log.debug("Fixed up the FHA time for " + p.getPeer().toBase64() + " to " + (new Date(min)));
        }
    }
    
    static long getLong(Properties props, String key) {
        String val = props.getProperty(key);
        if (val != null) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException nfe) {}
        }
        return 0;
    }

    private final static float getFloat(Properties props, String key) {
        String val = props.getProperty(key);
        if (val != null) {
            try {
                return Float.parseFloat(val);
            } catch (NumberFormatException nfe) {}
        }
        return 0.0f;
    }
    
    private void loadProps(Properties props, File file) throws IOException {
        InputStream fin = null;
        try {
            fin = new BufferedInputStream(new FileInputStream(file), 1);
            fin.mark(1);
            int c = fin.read(); 
            fin.reset();
            if (c == '#') {
                // uncompressed
                if (_log.shouldLog(Log.INFO))
                    _log.info("Loading uncompressed profile data from " + file.getName());
                DataHelper.loadProps(props, fin);
            } else {
                // compressed (or corrupt...)
                if (_log.shouldLog(Log.INFO))
                    _log.info("Loading compressed profile data from " + file.getName());
                DataHelper.loadProps(props, new GZIPInputStream(fin));
            }
        } finally {
            try {
                if (fin != null) fin.close();
            } catch (IOException e) {}
        }
    }

    private Hash getHash(String name) {
        if (name.length() < PREFIX.length() + 44)
            return null;
        String key = name.substring(PREFIX.length());
        key = key.substring(0, 44);
        //Hash h = new Hash();
        try {
            //h.fromBase64(key);
            byte[] b = Base64.decode(key);
            if (b == null)
                return null;
            Hash h = Hash.create(b);
            return h;
        } catch (RuntimeException dfe) {
            _log.warn("Invalid base64 [" + key + "]", dfe);
            return null;
        }
    }
    
    private File pickFile(PeerProfile profile) {
        String hash = profile.getPeer().toBase64();
        File dir = new File(_profileDir, DIR_PREFIX + hash.charAt(0));
        return new File(dir, PREFIX + hash + SUFFIX);
    }
    
    
    /** generate 1000 profiles */
/****
    public static void main(String args[]) {
        System.out.println("Generating 1000 profiles");
        File dir = new File("profiles");
        dir.mkdirs();
        byte data[] = new byte[32];
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < 1000; i++) {
            rnd.nextBytes(data);
            Hash peer = new Hash(data);
            try {
                File f = new File(dir, PREFIX + peer.toBase64() + SUFFIX);
                f.createNewFile();
                System.out.println("Created " + peer.toBase64());
            } catch (IOException ioe) {}
        }
        System.out.println("1000 peers created in " + dir.getAbsolutePath());
    }
****/
}
