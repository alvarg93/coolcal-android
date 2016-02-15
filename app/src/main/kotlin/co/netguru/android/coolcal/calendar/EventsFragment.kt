package co.netguru.android.coolcal.calendar

import android.database.Cursor
import android.database.CursorIndexOutOfBoundsException
import android.location.Location
import android.os.Bundle
import android.provider.CalendarContract
import android.support.v4.app.LoaderManager
import android.support.v4.content.CursorLoader
import android.support.v4.content.Loader
import android.support.v4.view.animation.FastOutSlowInInterpolator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import co.netguru.android.coolcal.R
import co.netguru.android.coolcal.app.BaseFragment
import co.netguru.android.coolcal.app.MainActivity
import co.netguru.android.coolcal.utils.AppPreferences
import co.netguru.android.coolcal.utils.Loaders
import co.netguru.android.coolcal.weather.OpenWeatherMap
import com.sothree.slidinguppanel.SlidingUpPanelLayout
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.fragment_events.*
import kotlinx.android.synthetic.main.view_calendar_today_summary.*
import org.joda.time.LocalDateTime
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import java.util.concurrent.TimeUnit

class EventsFragment : BaseFragment(), LoaderManager.LoaderCallbacks<Cursor>,
        SlidingUpPanelLayout.PanelSlideListener {

    private val DAY_MILLIS = TimeUnit.DAYS.toMillis(1)
    private var adapter: EventAdapter? = null
    private val interpolator = FastOutSlowInInterpolator()
    private val todayDt: Long = LocalDateTime(System.currentTimeMillis())
            .toLocalDate().toDateTimeAtStartOfDay().millis

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = EventAdapter(context, null, 0)
        initEventsLoading()
    }

    override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater!!.inflate(R.layout.fragment_events, container, false)
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dayOfWeekTextView.text = AppPreferences.formatDayOfWeekShort(todayDt)
        dayOfMonthTextView.text = AppPreferences.formatDayOfMonth(todayDt)
        eventsCalendarTabView.days = (0..5).map { i -> todayDt + i * DAY_MILLIS }
        eventsListView.adapter = adapter
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        (activity as MainActivity).slidingLayout.setDragView(eventsCalendarTabView)
    }

    override fun onDestroy() {
        activity.supportLoaderManager.destroyLoader(Loaders.EVENT_LOADER)
        super.onDestroy()
    }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<Cursor>? {
        return when (id) {
            Event.ID -> CursorLoader(context,
                    Event.EVENTS_URI,
                    Event.EVENTS_PROJECTION,
                    Event.EVENTS_DTSTART_SELECTION,
                    arrayOf(args?.getLong(Event.ARG_DT_FROM).toString(),
                            args?.getLong(Event.ARG_DT_TO).toString()),
                    CalendarContract.Events.DTSTART)

            else -> null
        }
    }

    private fun switchActiveDay(firstVisibleItem: Int) {
        try {
            val dt = adapter!!.getItemDayStart(firstVisibleItem)
            eventsCalendarTabView.switchDay(dt)
        } catch (e: CursorIndexOutOfBoundsException) {
            eventsCalendarTabView.switchDay(todayDt)
        }
    }

    private fun initScrollListener() {
        switchActiveDay(eventsListView.firstVisiblePosition)
        eventsListView.setOnScrollListener(object : AbsListView.OnScrollListener {
            var firstVisibleCache = 0

            override fun onScroll(view: AbsListView?, firstVisibleItem: Int,
                                  visibleItemCount: Int, totalItemCount: Int) {
                if (firstVisibleItem != firstVisibleCache) {
                    firstVisibleCache = firstVisibleItem
                    switchActiveDay(firstVisibleItem)
                }
            }

            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                // nothing
            }
        })
    }

    private fun initTodayStatistics(data: Cursor?) {
        if (data != null) {
            val tomorrowDt = todayDt + TimeUnit.DAYS.toMillis(1)
            val range = todayDt..tomorrowDt
            var todayEvents = 0
            var busyTodaySum = 0L
            if (data.moveToFirst()) {
                while (data.moveToNext()) {
                    val dtStart = data.getLong(Event.Projection.DTSTART.ordinal)
                    val dtEnd = data.getLong(Event.Projection.DTEND.ordinal)
                    if (dtStart in range || dtEnd in range) {
                        todayEvents += 1
                        busyTodaySum += dtEnd - dtStart
                    }
                }
            }
            data.moveToFirst()

            numberOfEventsTextView.text = "$todayEvents"
            busyForTextView.text = AppPreferences.formatPeriod(0, busyTodaySum)
        }
    }

    override fun onLoadFinished(loader: Loader<Cursor>?, data: Cursor?) {
        initTodayStatistics(data)
        adapter?.swapCursor(data)
        initScrollListener()
    }

    override fun onLoaderReset(loader: Loader<Cursor>?) {
        // nic
    }

    private fun requestForecast(location: Location) {
        val latitude = location.latitude
        val longitude = location.longitude
        OpenWeatherMap.api.getForecast(latitude, longitude)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    response ->
                    adapter?.forecastResponse = response
                }, {
                    error -> // todo: handle possible error - retry?
                })
    }

    private fun initEventsLoading() {
        val dtStop = todayDt + TimeUnit.DAYS.toMillis(5) // five days later
        val data = Bundle()
        data.putLong(Event.ARG_DT_FROM, todayDt)
        data.putLong(Event.ARG_DT_TO, dtStop)

        activity.supportLoaderManager.initLoader(Loaders.EVENT_LOADER, data, this)
    }

    override fun onLocationChanged(location: Location?) {
        super.onLocationChanged(location)
        if (location != null) {
            requestForecast(location)
        }
    }

    private fun crossfadePanelAlpha(slideOffset: Float) {
        val offset = interpolator.getInterpolation(slideOffset)
        panelHandleLayout.alpha = 1f - offset
        eventsCalendarTabView.alpha = offset
    }

    override fun onPanelExpanded(panel: View?) {
        crossfadePanelAlpha(1f)
    }

    override fun onPanelSlide(panel: View?, slideOffset: Float) {
        crossfadePanelAlpha(slideOffset)
    }

    override fun onPanelCollapsed(panel: View?) {
        crossfadePanelAlpha(0f)
    }

    override fun onPanelHidden(panel: View?) {
    }

    override fun onPanelAnchored(panel: View?) {
    }

    override fun onResume() {
        super.onResume()

        when ((activity as MainActivity).slidingLayout.panelState) {
            SlidingUpPanelLayout.PanelState.EXPANDED -> crossfadePanelAlpha(1f)
            else -> crossfadePanelAlpha(0f)
        }
    }
}