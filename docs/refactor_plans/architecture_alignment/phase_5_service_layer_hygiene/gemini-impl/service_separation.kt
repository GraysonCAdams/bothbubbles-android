// Phase 5 Implementation: Service Layer Hygiene

// 1. The Pure Business Logic (Singleton)
// Renamed to "Manager" to avoid confusion, or kept as "Service" but strictly logic.
@Singleton
class SocketManager @Inject constructor(
    private val socketClient: SocketClient,
    private val eventParser: SocketEventParser,
    @ApplicationContext private val context: Context // Only App Context allowed
) {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    fun connect() { ... }
    fun disconnect() { ... }
}

// 2. The Android Framework Component (Foreground Service)
// Responsible ONLY for keeping the process alive.
@AndroidEntryPoint
class SocketForegroundService : Service() {

    @Inject
    lateinit var socketManager: SocketManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Delegate logic to the singleton
        socketManager.connect()
        
        // Handle Android specific notification stuff
        startForeground(NOTIF_ID, createNotification())
        
        return START_STICKY
    }

    override fun onDestroy() {
        socketManager.disconnect()
        super.onDestroy()
    }
}

// Benefit:
// - SocketManager can be unit tested (mock Context).
// - SocketForegroundService is just a shell.
