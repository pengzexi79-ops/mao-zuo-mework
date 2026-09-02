using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using System.Text.RegularExpressions;
using System.Security.Cryptography;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

internal static class Program
{
    internal const string Title = "\u732b\u4f5c\u00b7Mework";
    private static Mutex instanceMutex;
    private const uint ProcessQueryLimitedInformation = 0x1000;
    private const int RestoreWindow = 9;
    private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr FindWindow(string c, string n);
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int maxCount);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr hWnd, int command);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern IntPtr OpenProcess(uint access, bool inheritHandle, uint processId);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool QueryFullProcessImageName(IntPtr process, int flags, StringBuilder filename, ref int size);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);

    [STAThread]
    private static void Main()
    {
        string appDir = Path.GetFullPath(AppDomain.CurrentDomain.BaseDirectory).TrimEnd('\\');
        RuntimeHost.Log(appDir, "Desktop launcher invoked.");
        bool created;
        string mutexName = BuildInstanceMutexName(appDir);
        RuntimeHost.Log(appDir, "Using installation-scoped desktop instance mutex.");
        instanceMutex = new Mutex(true, mutexName, out created);
        if (!created)
        {
            RuntimeHost.Log(appDir, "Another desktop launcher owns the instance mutex; looking for its window.");
            IntPtr existing = FindExistingWindow(appDir, 50, 100);
            if (existing != IntPtr.Zero)
            {
                ShowWindow(existing, RestoreWindow);
                SetForegroundWindow(existing);
                RuntimeHost.Log(appDir, "Existing desktop window activated.");
            }
            else
            {
                RuntimeHost.Log(appDir, "Instance mutex was held but no visible desktop window was found.");
                MessageBox.Show(
                    "猫作已经在运行，但没有找到可显示的桌面窗口。\r\n\r\n" +
                    "请稍等片刻后再次点击桌面图标；如果仍然没有窗口，请查看 data\\logs\\desktop-launcher.log。",
                    Title, MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            instanceMutex.Close();
            return;
        }
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        try
        {
            Application.Run(new MainWindow());
        }
        catch (Exception ex)
        {
            RuntimeHost.Log(appDir, "Desktop launcher crashed before the main window could remain visible: " + RuntimeHost.SafeError(ex));
            MessageBox.Show(
                "猫作无法创建桌面窗口。\r\n\r\n" + RuntimeHost.SafeError(ex) +
                "\r\n\r\n请查看 data\\logs\\desktop-launcher.log。",
                Title, MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        GC.KeepAlive(instanceMutex);
    }

    private static string BuildInstanceMutexName(string appDir)
    {
        string normalized = Path.GetFullPath(appDir)
            .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
            .ToUpperInvariant();
        using (SHA256 sha256 = SHA256.Create())
        {
            byte[] digest = sha256.ComputeHash(Encoding.UTF8.GetBytes(normalized));
            StringBuilder suffix = new StringBuilder(digest.Length * 2);
            for (int i = 0; i < digest.Length; i++) suffix.Append(digest[i].ToString("x2"));
            return "Local\\MeworkDesktop-" + suffix.ToString();
        }
    }

    private static IntPtr FindExistingWindow(string appDir, int attempts, int delayMilliseconds)
    {
        string executablePath = Path.GetFullPath(Application.ExecutablePath);
        for (int attempt = 0; attempt < attempts; attempt++)
        {
            IntPtr exact = FindWindow(null, Title);
            IntPtr match = WindowBelongsToApplication(exact, executablePath) ? exact : FindWindowByProcessPath(executablePath);
            if (match != IntPtr.Zero) return match;
            if (attempt + 1 < attempts) Thread.Sleep(delayMilliseconds);
        }
        return IntPtr.Zero;
    }

    private static IntPtr FindWindowByProcessPath(string executablePath)
    {
        IntPtr found = IntPtr.Zero;
        EnumWindows(delegate (IntPtr hWnd, IntPtr lParam)
        {
            if (!IsWindowVisible(hWnd)) return true;
            StringBuilder title = new StringBuilder(256);
            GetWindowText(hWnd, title, title.Capacity);
            if (!String.Equals(title.ToString(), Title, StringComparison.Ordinal)) return true;
            if (WindowBelongsToApplication(hWnd, executablePath))
            {
                found = hWnd;
                return false;
            }
            return true;
        }, IntPtr.Zero);
        return found;
    }

    private static bool WindowBelongsToApplication(IntPtr hWnd, string executablePath)
    {
        if (hWnd == IntPtr.Zero) return false;
        uint processId;
        GetWindowThreadProcessId(hWnd, out processId);
        if (processId == 0) return false;
        string ownerPath = GetProcessPath(processId);
        return !String.IsNullOrEmpty(ownerPath) &&
            String.Equals(Path.GetFullPath(ownerPath), Path.GetFullPath(executablePath), StringComparison.OrdinalIgnoreCase);
    }

    private static string GetProcessPath(uint processId)
    {
        IntPtr process = OpenProcess(ProcessQueryLimitedInformation, false, processId);
        if (process == IntPtr.Zero) return null;
        try
        {
            StringBuilder path = new StringBuilder(1024);
            int size = path.Capacity;
            return QueryFullProcessImageName(process, 0, path, ref size) ? path.ToString() : null;
        }
        catch { return null; }
        finally { CloseHandle(process); }
    }
}

internal sealed class MainWindow : Form
{
    private readonly Panel loadingPanel;
    private readonly Label statusLabel;
    private readonly Label detailLabel;
    private readonly ProgressBar progressBar;
    private readonly Button retryButton;
    private readonly Button closeButton;
    private WebView2 webView;
    private readonly string appDir;
    private bool startupInProgress;
    private int port;

    internal MainWindow()
    {
        appDir = Path.GetFullPath(AppDomain.CurrentDomain.BaseDirectory).TrimEnd('\\');
        Text = Program.Title;
        StartPosition = FormStartPosition.CenterScreen;
        Width = 1440; Height = 900; MinimumSize = new Size(1100, 720);
        WindowState = FormWindowState.Maximized; BackColor = Color.White;
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

        webView = null;
        loadingPanel = new Panel(); loadingPanel.Dock = DockStyle.Fill; loadingPanel.BackColor = Color.White;
        Controls.Add(loadingPanel); loadingPanel.BringToFront();

        Label title = new Label(); title.AutoSize = true;
        title.Font = new Font("Microsoft YaHei UI", 20F, FontStyle.Bold); title.Text = Program.Title;
        loadingPanel.Controls.Add(title);
        statusLabel = new Label(); statusLabel.AutoSize = false; statusLabel.TextAlign = ContentAlignment.MiddleCenter;
        statusLabel.Font = new Font("Microsoft YaHei UI", 10F); statusLabel.Text = "\u6b63\u5728\u542f\u52a8\u672c\u673a\u670d\u52a1...";
        loadingPanel.Controls.Add(statusLabel);
        detailLabel = new Label(); detailLabel.AutoSize = false; detailLabel.TextAlign = ContentAlignment.TopCenter;
        detailLabel.Font = new Font("Microsoft YaHei UI", 9F); detailLabel.ForeColor = Color.FromArgb(90, 90, 90);
        detailLabel.Visible = false; loadingPanel.Controls.Add(detailLabel);
        progressBar = new ProgressBar(); progressBar.Style = ProgressBarStyle.Marquee;
        progressBar.MarqueeAnimationSpeed = 24; loadingPanel.Controls.Add(progressBar);
        retryButton = new Button(); retryButton.Text = "重试启动"; retryButton.Width = 112; retryButton.Height = 34;
        retryButton.Visible = false; retryButton.Click += OnRetryClicked; loadingPanel.Controls.Add(retryButton);
        closeButton = new Button(); closeButton.Text = "关闭"; closeButton.Width = 92; closeButton.Height = 34;
        closeButton.Visible = false; closeButton.Click += delegate { Close(); }; loadingPanel.Controls.Add(closeButton);
        loadingPanel.Resize += delegate
        {
            int x = loadingPanel.ClientSize.Width / 2, y = loadingPanel.ClientSize.Height / 2;
            title.Location = new Point(x - title.Width / 2, y - 70);
            statusLabel.SetBounds(x - 300, y - 20, 600, 32); progressBar.SetBounds(x - 180, y + 24, 360, 8);
            detailLabel.SetBounds(x - 360, y + 44, 720, 42);
            retryButton.Location = new Point(x - retryButton.Width - 8, y + 106);
            closeButton.Location = new Point(x + 8, y + 106);
        };
        Shown += OnShown;
    }

    private async void OnShown(object sender, EventArgs e)
    {
        await StartApplicationAsync();
    }

    private async void OnRetryClicked(object sender, EventArgs e)
    {
        await StartApplicationAsync();
    }

    private async Task StartApplicationAsync()
    {
        if (startupInProgress) return;
        startupInProgress = true;
        retryButton.Visible = false;
        closeButton.Visible = false;
        detailLabel.Visible = false;
        progressBar.Visible = true;
        statusLabel.Text = "正在启动本机服务...";
        RuntimeHost.Log(appDir, "Desktop startup attempt started.");
        try
        {
            PrepareWebView();
            port = await Task.Run(delegate { return RuntimeHost.EnsureRunning(appDir); });
            statusLabel.Text = "\u6b63\u5728\u52a0\u8f7d\u732b\u4f5c\u5de5\u4f5c\u53f0...";
            string profileDir = Path.Combine(appDir, "data", "desktop-webview");
            Directory.CreateDirectory(profileDir);
            CoreWebView2Environment environment = await CoreWebView2Environment.CreateAsync(null, profileDir);
            await webView.EnsureCoreWebView2Async(environment);
            webView.CoreWebView2.Settings.IsStatusBarEnabled = false;
            webView.CoreWebView2.Settings.AreDevToolsEnabled = false;
            webView.CoreWebView2.Settings.AreBrowserAcceleratorKeysEnabled = false;
            webView.CoreWebView2.NewWindowRequested += OnNewWindowRequested;
            webView.Source = new Uri("http://127.0.0.1:" + port + "/");
            loadingPanel.Visible = false; webView.Visible = true; webView.Focus();
            RuntimeHost.Log(appDir, "Desktop startup completed on port " + port + ".");
        }
        catch (Exception ex)
        {
            string safeError = RuntimeHost.SafeError(ex);
            RuntimeHost.Log(appDir, "Desktop startup failed: " + safeError);
            ShowStartupFailure(safeError);
        }
        finally
        {
            startupInProgress = false;
        }
    }

    private void PrepareWebView()
    {
        if (webView != null)
        {
            Controls.Remove(webView);
            try { webView.Dispose(); } catch { }
        }
        webView = new WebView2();
        webView.Dock = DockStyle.Fill;
        webView.Visible = false;
        Controls.Add(webView);
        Controls.SetChildIndex(webView, Controls.Count - 1);
        loadingPanel.BringToFront();
    }

    private void ShowStartupFailure(string error)
    {
        loadingPanel.Visible = true;
        if (webView != null) webView.Visible = false;
        progressBar.Visible = false;
        statusLabel.Text = "猫作桌面启动失败，但窗口仍保持打开";
        detailLabel.Text = "原因：" + error + "\r\n请查看 data\\logs\\desktop-launcher.log 获取完整诊断。";
        detailLabel.Visible = true;
        retryButton.Visible = true;
        closeButton.Visible = true;
        loadingPanel.BringToFront();
    }

    private void OnNewWindowRequested(object sender, CoreWebView2NewWindowRequestedEventArgs e)
    {
        string localPrefix = "http://127.0.0.1:" + port + "/";
        if (e.Uri.StartsWith(localPrefix, StringComparison.OrdinalIgnoreCase)) webView.CoreWebView2.Navigate(e.Uri);
        else
        {
            ProcessStartInfo info = new ProcessStartInfo(e.Uri); info.UseShellExecute = true; Process.Start(info);
        }
        e.Handled = true;
    }
}

internal static class RuntimeHost
{
    private const int DefaultPort = 8760;

    internal static int EnsureRunning(string appDir)
    {
        EnsureEnvironment(appDir);
        string envPath = Path.Combine(appDir, ".env");
        int port = ReadPort(envPath);
        if (OwnServiceHealthy(port, appDir)) return port;
        if (PortIsListening(port))
        {
            port = FindFreePort(DefaultPort, 200);
            PersistPort(envPath, port);
            Log(appDir, "Port conflict detected; selected " + port + ".");
        }
        StartBatch(Path.Combine(appDir, "start.bat"), appDir, false, true, 0);
        for (int attempt = 0; attempt < 360; attempt++)
        {
            if (OwnServiceHealthy(port, appDir))
            {
                Log(appDir, "Backend healthy on port " + port + ".");
                return port;
            }
            Thread.Sleep(500);
        }
        throw new InvalidOperationException("The local service did not become ready within three minutes.");
    }

    private static void EnsureEnvironment(string appDir)
    {
        string envPath = Path.Combine(appDir, ".env");
        if (File.Exists(envPath)) return;
        int port = FindFreePort(DefaultPort, 200);
        int exitCode = StartBatch(Path.Combine(appDir, "ensure_env.bat"), appDir, true, false, port);
        if (exitCode != 0 || !File.Exists(envPath))
            throw new InvalidOperationException("Could not create the private local configuration.");
    }

    private static int StartBatch(string path, string appDir, bool wait, bool skipBrowser, int preferredPort)
    {
        if (!File.Exists(path)) throw new FileNotFoundException("Required startup file is missing.", path);
        ProcessStartInfo info = new ProcessStartInfo();
        info.FileName = Environment.GetEnvironmentVariable("ComSpec") ?? "cmd.exe";
        info.Arguments = "/d /c call \"" + path + "\"";
        info.WorkingDirectory = appDir;
        info.UseShellExecute = false; info.CreateNoWindow = true; info.WindowStyle = ProcessWindowStyle.Hidden;
        if (skipBrowser) info.EnvironmentVariables["APP_SKIP_BROWSER"] = "true";
        if (preferredPort > 0) info.EnvironmentVariables["APP_PORT"] = preferredPort.ToString();
        Process process = Process.Start(info);
        if (process == null) throw new InvalidOperationException("Windows could not create the local service process.");
        if (!wait) return 0;
        if (!process.WaitForExit(60000))
        {
            try { process.Kill(); } catch { }
            throw new InvalidOperationException("Local configuration timed out.");
        }
        return process.ExitCode;
    }

    private static bool OwnServiceHealthy(int port, string appDir)
    {
        try
        {
            HttpWebRequest request = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:" + port + "/api/system/env");
            request.Method = "GET"; request.Proxy = null; request.Timeout = 1800; request.ReadWriteTimeout = 1800;
            string json;
            using (HttpWebResponse response = (HttpWebResponse)request.GetResponse())
            using (Stream stream = response.GetResponseStream())
            using (StreamReader reader = new StreamReader(stream, Encoding.UTF8))
            {
                if (response.StatusCode != HttpStatusCode.OK) return false;
                json = reader.ReadToEnd();
            }
            JavaScriptSerializer serializer = new JavaScriptSerializer();
            Dictionary<string, object> envelope = serializer.Deserialize<Dictionary<string, object>>(json);
            object dataValue;
            Dictionary<string, object> data = null;
            if (envelope != null && envelope.TryGetValue("data", out dataValue))
                data = dataValue as Dictionary<string, object>;
            return data != null &&
                (PathBelongsToApp(data, "outputDir", appDir) ||
                 PathBelongsToApp(data, "materialsDir", appDir) ||
                 PathBelongsToApp(data, "localPython", appDir) ||
                 PathBelongsToApp(data, "portablePython", appDir));
        }
        catch { return false; }
    }

    private static bool PathBelongsToApp(Dictionary<string, object> data, string key, string appDir)
    {
        object value;
        if (!data.TryGetValue(key, out value) || value == null) return false;
        try
        {
            string root = Path.GetFullPath(appDir).TrimEnd('\\') + "\\";
            string candidate = Path.GetFullPath(Convert.ToString(value)).TrimEnd('\\') + "\\";
            return candidate.StartsWith(root, StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    private static int ReadPort(string envPath)
    {
        foreach (string raw in File.ReadAllLines(envPath, Encoding.UTF8))
        {
            string line = raw.Trim();
            if (!line.StartsWith("PORT=", StringComparison.OrdinalIgnoreCase)) continue;
            int port;
            if (Int32.TryParse(line.Substring(5).Trim(), out port) && port > 0 && port <= 65535) return port;
        }
        return DefaultPort;
    }

    private static void PersistPort(string envPath, int port)
    {
        List<string> lines = new List<string>(File.ReadAllLines(envPath, Encoding.UTF8));
        bool replaced = false;
        for (int index = 0; index < lines.Count; index++)
        {
            if (!lines[index].TrimStart().StartsWith("PORT=", StringComparison.OrdinalIgnoreCase)) continue;
            lines[index] = "PORT=" + port; replaced = true; break;
        }
        if (!replaced) lines.Add("PORT=" + port);
        File.WriteAllLines(envPath, lines.ToArray(), new UTF8Encoding(false));
    }

    private static int FindFreePort(int preferred, int range)
    {
        for (int port = preferred; port < preferred + range; port++)
        {
            if (PortIsListening(port)) continue;
            TcpListener listener = null;
            try
            {
                listener = new TcpListener(IPAddress.Loopback, port);
                listener.Server.ExclusiveAddressUse = true; listener.Start(); return port;
            }
            catch (SocketException) { }
            finally { if (listener != null) try { listener.Stop(); } catch { } }
        }
        throw new InvalidOperationException("No free local application port is available.");
    }

    private static bool PortIsListening(int port)
    {
        try
        {
            foreach (IPEndPoint endpoint in IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners())
                if (endpoint.Port == port) return true;
        }
        catch { }
        return false;
    }

    internal static void Log(string appDir, string message)
    {
        try
        {
            string path = Path.Combine(appDir, "data", "logs", "desktop-launcher.log");
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            File.AppendAllText(path, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + message + Environment.NewLine, Encoding.UTF8);
        }
        catch { }
    }

    internal static string SafeError(Exception exception)
    {
        string message = exception == null ? "未知启动错误。" : exception.GetBaseException().Message;
        if (String.IsNullOrWhiteSpace(message)) message = "未知启动错误。";
        message = message.Replace("\r", " ").Replace("\n", " ").Trim();
        message = Regex.Replace(message, "(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s]+", "$1[已隐藏]");
        message = Regex.Replace(message, "(?i)\\b(sk|key|token|secret)[-_:=/][A-Za-z0-9._-]{8,}", "$1-[已隐藏]");
        return message.Length > 320 ? message.Substring(0, 320) + "..." : message;
    }
}
