package com.taobao.atlas.update.util;

import android.os.Build;
import android.taobao.atlas.bundleInfo.AtlasBundleInfoManager;
import android.taobao.atlas.framework.Atlas;
import android.taobao.atlas.framework.BundleImpl;
import android.taobao.atlas.framework.Framework;
import android.taobao.atlas.framework.bundlestorage.BundleArchive;
import android.taobao.atlas.runtime.RuntimeVariables;
import android.taobao.atlas.util.ApkUtils;
import android.taobao.atlas.versionInfo.BaselineInfoManager;
import android.text.TextUtils;
import android.util.Pair;

import com.taobao.atlas.dexmerge.DexMergeClient;
import com.taobao.atlas.dexmerge.MergeCallback;
import com.taobao.atlas.dexmerge.MergeObject;
import com.taobao.atlas.update.exception.MergeException;
import com.taobao.atlas.update.model.UpdateInfo;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Created by guanjie on 15/10/8.
 */
public class PatchMerger {

    private UpdateInfo updateInfo;
    private File patchFile;
    private MergeCallback mergeCallback;
    private ZipFile apkZip;
    private static int BUFFEREDSIZE = 1024;

    public Map<String, Pair<String,UpdateInfo.Item>> mergeOutputs = new HashMap<>();
    private static boolean supportMerge;
    private static String MAIN_DEX = "com.taobao.maindex";
    static {
        supportMerge = Build.VERSION.SDK_INT < 21;
    }


    public PatchMerger(UpdateInfo updateInfo, File patchFile, MergeCallback mergeCallback) throws MergeException {
        this.updateInfo = updateInfo;
        this.patchFile = patchFile;
        this.mergeCallback = mergeCallback;

        try {
            this.apkZip = new ZipFile(RuntimeVariables.androidApplication.getApplicationInfo().sourceDir);
        } catch (IOException e) {
            throw new MergeException(e);
        }


    }

    public void merge() throws MergeException, IOException {

        try {
            File outputDirectory = updateInfo.workDir;
            File oringnalDir = new File(outputDirectory.getParentFile(), "orignal_" + updateInfo.baseVersion);
            if (!outputDirectory.exists()){
                outputDirectory.mkdirs();
            }
            if (!oringnalDir.exists()){
                oringnalDir.mkdirs();
            }
            ZipFile patchZip = new ZipFile(patchFile);
            Pair<File, String>[] updateBundles = new Pair[updateInfo.updateBundles.size()];
            List<MergeObject> toMergeList = new ArrayList<MergeObject>();
            //find original bundle and store in pair struct
            for (int x = 0; x < updateInfo.updateBundles.size(); x++) {
                UpdateInfo.Item item = updateInfo.updateBundles.get(x);
                String bundleName = item.name;
                String entryNamePrefix = String.format("%s%s", "lib", bundleName.replace(".", "_"));
                String entryName = entryNamePrefix + ".so";
                ZipEntry entry = patchZip.getEntry(entryName);
                if (patchZip.getEntry(entryName) != null && !supportMerge(bundleName)) {
                    //全量部署
                    File targetBundle = new File(outputDirectory, entryName);
                    OutputStream outputStream = new FileOutputStream(targetBundle);
                    InputStream inputStream = patchZip.getInputStream(entry);
                    copyStream(inputStream, outputStream);
                    mergeOutputs.put(bundleName, new Pair<>(targetBundle.getAbsolutePath(), item));
                } else {
                    File baselineBundle = findOriginalBundleFile(bundleName, oringnalDir.getAbsolutePath(), item);
                    if(item.srcVersion.equals("-1")){
                        //回滚到基线的bundle
                        mergeOutputs.put(bundleName,new Pair<>(baselineBundle.getAbsolutePath(),item));
                    }else {
                        //差量部署
                        File originalBundle = findOriginalBundleFile(bundleName, oringnalDir.getAbsolutePath(), item);
                        if (originalBundle != null && originalBundle.exists()) {
                            updateBundles[x] = new Pair<File, String>(originalBundle, bundleName);
                            File targetBundle = new File(outputDirectory, "lib" + bundleName.replace(".", "_") + ".so");
                            if (targetBundle.exists()) {
                                targetBundle.delete();
                            }
                            toMergeList.add(new MergeObject(updateBundles[x].first.getAbsolutePath(), updateBundles[x].second, targetBundle.getAbsolutePath()));
                            mergeOutputs.put(bundleName, new Pair<>(targetBundle.getAbsolutePath(), item));
                        }
                    }
                }
            }

            if (toMergeList.size() > 0) {

                DexMergeClient dexMergeClient = getMergeClient();

                boolean mergeFinish = dexMergeClient.dexMerge(patchFile.getAbsolutePath(),toMergeList, true);

                dexMergeClient.unPrepare();

                if (!mergeFinish) {
                    throw new MergeException("merge failed!");
                }
            }


        } catch (Exception e){
            e.printStackTrace();
            throw new MergeException("merge failed!");
        } finally{
            if (apkZip != null) {
                try {
                    apkZip.close();
                } catch (Throwable e) {
                }
            }

        }

    }



    /**
     * be not call in main thread
     */


    /**
     * get original bundle
     *
     * @param bundleName
     * @return
     */
    public File findOriginalBundleFile(String bundleName, String bundleDirIfNeedCreate, UpdateInfo.Item item) throws IOException {
        if (bundleName.equals(MAIN_DEX)){
            if(item.dexPatchVersion<=0) {
                return new File(RuntimeVariables.androidApplication.getApplicationInfo().sourceDir);
            }else{
                if(BaselineInfoManager.instance().getBaseBundleVersion("com.taobao.maindex").equals(item.srcVersion)){
                    File old = new File(RuntimeVariables.androidApplication.getFilesDir(),"storage/com.taobao.maindex/"+item.srcVersion+"/com_taobao_maindex.zip");
                    if(old.exists()){
                        return old;
                    }else{
                        throw new IOException("can not find original com_taobao_maindex.zip");
                    }
                }
            }
        }
        if(item.reset){
            return getOriginalBundleFromApk(bundleName,bundleDirIfNeedCreate);
        }
        File oldBundle = null;

        if (TextUtils.isEmpty(item.srcVersion)) {
            throw new IllegalStateException("src version can not be null");
        } else {
            BundleImpl impl = (BundleImpl) Atlas.getInstance().getBundle(bundleName);
            if (impl != null && !BaselineInfoManager.instance().isDexPatched(bundleName)) {
                String path = impl.getArchive().getCurrentRevision().getRevisionDir().getAbsolutePath();
                if(!path.contains(BundleArchive.DEXPATCH_DIR) && AtlasBundleInfoManager.instance().getBundleInfo(bundleName).getUnique_tag().equals(item.srcVersion)){
                    oldBundle = impl.getArchive().getArchiveFile();
                }
            } else {
                oldBundle = Framework.getInstalledBundle(bundleName, item.srcVersion);
            }
        }
        if (oldBundle == null && AtlasBundleInfoManager.instance().getBundleInfo(bundleName).getUnique_tag().equals(item.srcVersion) && !BaselineInfoManager.instance().isUpdated(bundleName)) {
            oldBundle = getOriginalBundleFromApk(bundleName,bundleDirIfNeedCreate);
        }
        if(oldBundle!=null || !AtlasBundleInfoManager.instance().isInternalBundle(bundleName)) {
            return oldBundle;
        }else{
            throw new IOException("can not find valid src bundle of " + bundleName);
        }
    }

    private File getOriginalBundleFromApk(String bundleName,String bundleDirIfNeedCreate) throws IOException{
        String oldBundleFileName = String.format("lib%s.so", bundleName.replace(".", "_"));
        File libDir = new File(RuntimeVariables.androidApplication.getFilesDir().getParentFile(), "lib");
        File oldBundle = new File(libDir,oldBundleFileName);
        if(!oldBundle.exists()){
            oldBundle = new File(RuntimeVariables.androidApplication.getApplicationInfo().nativeLibraryDir,oldBundleFileName);
        }
        if (oldBundle.exists()) {
            return oldBundle;
        } else {
            if (apkZip == null) {
                apkZip = new ZipFile(RuntimeVariables.androidApplication.getApplicationInfo().sourceDir);
            }
            String entryName = String.format("lib/armeabi/%s", oldBundleFileName);
            if (apkZip.getEntry(entryName) != null) {
                InputStream inputStream = apkZip.getInputStream(apkZip.getEntry(entryName));
                oldBundle = new File(bundleDirIfNeedCreate, oldBundleFileName);
                ApkUtils.copyInputStreamToFile(inputStream, oldBundle);
                return oldBundle;
            }
        }
        return oldBundle;
    }

    private DexMergeClient getMergeClient() {
        DexMergeClient mergeClient = new DexMergeClient(mergeCallback);
        boolean result = mergeClient.prepare();
        if (result) {
            return mergeClient;
        }
        throw new RuntimeException("prepare client error");
    }


    private boolean supportMerge(String bundleName) {

        return supportMerge&&bundleName.equals(MAIN_DEX);

    }

    private void copyStream(InputStream in, OutputStream out) throws IOException {

        try {
            int c;
            byte[] by = new byte[BUFFEREDSIZE];
            while ((c = in.read(by)) != -1) {
                out.write(by, 0, c);
            }
            out.flush();
        } catch (IOException e) {
            throw e;
        } finally {
            closeQuitely(out);
            closeQuitely(in);
        }
    }


    private void closeQuitely(Closeable stream) {
        try {
            if (stream != null)
                stream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
