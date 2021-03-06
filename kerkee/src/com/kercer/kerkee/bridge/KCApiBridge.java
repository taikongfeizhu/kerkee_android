package com.kercer.kerkee.bridge;

import java.io.File;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.kercer.kerkee.downloader.KCDownloader.KCScheme;
import com.kercer.kerkee.log.KCLog;
import com.kercer.kerkee.net.KCHttpServer;
import com.kercer.kerkee.util.KCTaskExecutor;
import com.kercer.kerkee.util.KCUtil;
import com.kercer.kerkee.webview.KCWebPath;
import com.kercer.kerkee.webview.KCWebView;

/**
 * 
 * @author zihong
 *
 */
public class KCApiBridge
{

    private final static KCClassMrg mClassMrg = new KCClassMrg();
    private static String mJS;

    public static void initJSBridgeEnvironment(KCWebView aWebview, KCScheme aScheme)
    {
    	if (!aScheme.equals(KCScheme.FILE) && !KCHttpServer.isRunning())
    		return;
    	
        if (mJS == null)
        {
            KCWebPath webPath = aWebview.getWebPath();
            mJS = "var scriptBlock = document.createElement('script');";
            mJS += "scriptBlock.src='';";
            String jsFilePath = "file://" + webPath.getJSBridgePath();
            String jsHttpPath = KCHttpServer.getLocalHostUrl() + webPath.getJSBridgeRelativePath();
            String jsUri = aScheme.equals(KCScheme.FILE) ? jsFilePath : jsHttpPath;
            mJS += "scriptBlock.src='" + jsUri + "';";
            mJS += "scriptBlock.type = 'text/javascript';";
            mJS += "scriptBlock.language = 'javascript';";
//            mJS += "scriptBlock.onload=function(){ApiBridge.onBridgeInitComplete(function(){ApiBridge.onNativeInitComplete();});};";
            mJS += "scriptBlock.onload=function(){console.log('--- jsBridgeClient onLoad ---');};";
            mJS += "document.getElementsByTagName('head')[0].appendChild(scriptBlock);";
        }
        
        callJSOnMainThread(aWebview, mJS);
    }

    public static boolean registClass(KCClass aClass)
    {
        return mClassMrg.registClass(aClass);
    }

    public static boolean registClass(String aJSObjectName, Class<?> aClass)
    {
        return mClassMrg.registClass(aJSObjectName, aClass);
    }

    public static boolean registJSBridgeClient(Class<?> aClass)
    {
        removeClass(KCJSDefine.kJS_jsBridgeClient);
        return registClass(KCJSDefine.kJS_jsBridgeClient, aClass);
    }

    public static void removeClass(String aJSObjectName)
    {
        mClassMrg.removeClass(aJSObjectName);
    }

    public static String callNative(KCWebView webView, String aJSONStr)
    {
        if (!"".equals(aJSONStr))
        {
            KCClassParser parser = new KCClassParser(aJSONStr);
            try
            {
                String className = parser.getJSClzName();
                String methodName = parser.getJSMethodName();
                KCArgList argList = parser.getArgList();

                KCClass clz = mClassMrg.getClass(className);
                if (clz != null)
                {
                    clz.addMethod(methodName, argList);
                }

                KCMethod method = null;
                
                KCLog.d(">>>>>>>>> callNative: " + className + "." + methodName + ", " + method + ", " + aJSONStr);
                
                boolean isArgList = true;

                if (argList.size() > 0)
                {
                    try
                    {
                        method = clz.getMethod(methodName, KCWebView.class, KCArgList.class);
                        
                        //use this when method get from cache,Compatibility with previous versions
                        Class<?>[] argsType = method.getNavMethod().getParameterTypes();
                        for (Class<?> tmpClass : argsType)
                        {
                            if (tmpClass == JSONObject.class)
                            {
                                isArgList = false;
                                break;
                            }
                        }
                    }
                    catch (Exception e)
                    {
                        isArgList = false;
                        method = clz.getMethod(methodName, KCWebView.class, JSONObject.class);
                    }
                }
                else
                {
                    method = clz.getMethod(methodName, KCWebView.class);
                }

                String result;
                if (method.getArgsCount() == 2)
                {
                    Object value = null;
                    if (isArgList)
                        value = argList;
                    else
                        value = parser.getArgsJSON();

                    result = (String) method.invoke(null, webView, value);
                }
                else
                {
                    result = (String) method.invoke(null, webView);
                }
                return result == null ? "" : result;

            }
            catch (Exception e)
            {
                KCLog.e("ERROR JS call " + parser.getJSClzName() + "::" + parser.getJSMethodName());
                e.printStackTrace();
            }
        }
        return "";
    }


    public static void callbackJSOnHitPageBottom(KCWebView aWebview)
    {
        callJS(aWebview, "if(jsBridgeClient && jsBridgeClient.onHitPageBottom) jsBridgeClient.onHitPageBottom()");
    }

    public static void JSLog(KCWebView aWebview, JSONObject aJSONObject)
    {
        KCLog.e(aJSONObject.toString());
    }
    
    public static void onBridgeInitComplete(KCWebView aWebView, KCArgList aArgList)
    {
        KCLog.e(aArgList.toString());
        aWebView.documentReady(true);
        String callbackId = (String)aArgList.getArgValue(KCJSDefine.kJS_callbackId);
        callbackJS(aWebView, callbackId);
    }

    /**
     * @param aWebview
     * destroy webview the hackery way, this method is used to destroy webview.
     * since on Android 4.1.x, directly calling WebView.destroy() sometimes
     * causes WebViewCoreThread to wait indefinitely, which stops the WebView from
     * working as usual.
     */
    public static void hackDestroyWebView(final KCWebView aWebview)
    {
        KCLog.d(">>>>>> hackDestroyWebView called.");
        KCTaskExecutor.scheduleTaskOnUiThread(2000, new Runnable()
        {
            @Override
            public void run()
            {
                ((KCWebView) aWebview).doDestroy();
            }
        });
    }

    public static void setHitPageBottomThreshold(KCWebView aWebview, JSONObject aJSONObject)
    {
        try
        {
            ((KCWebView) aWebview).setHitPageBottomThreshold(aJSONObject.getInt("threshold"));
        }
        catch (JSONException e)
        {
        }
    }
    
    
    
    /********************************************************/
    /*
     * js opt
     */
    /********************************************************/
    
    public static void callJSOnMainThread(final KCWebView aWebview, final String aJS)
    {
        KCTaskExecutor.runTaskOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                aWebview.loadUrlExt("javascript:" + aJS);
            }
        });
    }
    
    public static void callJS(final KCWebView aWebview, final String aJS)
    {
        if (aWebview == null)
            return;
        aWebview.loadUrlExt("javascript:" + aJS);
    }
    
    public static void callJSFunctionOnMainThread(final KCWebView aWebview, String aFunName, String aArgs)
    {
        StringBuilder sb = KCUtil.getThreadSafeStringBuilder().append(aFunName).append('(');
        if (aArgs != null)
            sb.append(aArgs);
        sb.append(')');
        callJSOnMainThread(aWebview, sb.toString());
    }
    
    public static void callJSFunctionOnMainThread(final KCWebView aWebview, String aFunName, String aArgs1, String aArgs2)
    {
        StringBuilder sb = KCUtil.getThreadSafeStringBuilder().append(aFunName).append('(');
        if (aArgs1 != null)
            sb.append(aArgs1);
        if (aArgs2 != null) {
        	sb.append(','+aArgs2);
        }
        sb.append(')');
        callJSOnMainThread(aWebview, sb.toString());
    }

    public static void callbackJS(KCWebView aWebview, String aCallbackId)
    {
        StringBuilder sb = KCUtil.getThreadSafeStringBuilder().append("ApiBridge.onCallback(").append(aCallbackId).append(')');
        callJSOnMainThread(aWebview, sb.toString());
    }
    
    public static void callbackJS(KCWebView aWebview, String aCallbackId, String aStr)
    {
        StringBuilder sb = KCUtil.getThreadSafeStringBuilder().append("ApiBridge.onCallback(");
        sb.append(aCallbackId).append(", '").append(aStr).append("')");
        callJSOnMainThread(aWebview, sb.toString());
    }
    
    public static void callbackJS(KCWebView aWebview, String aCallbackId, JSONObject aJSONObject)
    {
        StringBuilder sb = KCUtil.getThreadSafeStringBuilder().append("ApiBridge.onCallback(");
        sb.append(aCallbackId).append(',').append(aJSONObject.toString()).append(')');
        callJSOnMainThread(aWebview, sb.toString());
    }

    public static void callbackJS(KCWebView aWebview, String aCallbackId, JSONArray aJSONArray)
    {
        StringBuilder sb = KCUtil.getThreadSafeStringBuilder().append("ApiBridge.onCallback(");
        sb.append(aCallbackId).append(',').append(aJSONArray.toString()).append(')');
        callJSOnMainThread(aWebview, sb.toString());
    }

}
