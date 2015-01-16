package org.wikipedia.search;

import org.wikipedia.BackPressedHandler;
import org.wikipedia.PageTitle;
import org.wikipedia.R;
import org.wikipedia.Utils;
import org.wikipedia.WikipediaApp;
import org.wikipedia.analytics.SearchFunnel;
import org.wikipedia.concurrency.SaneAsyncTask;
import org.wikipedia.events.WikipediaZeroStateChangeEvent;
import org.wikipedia.history.HistoryEntry;
import org.wikipedia.page.PageActivity;
import com.squareup.otto.Subscribe;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v7.widget.SearchView;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SearchArticlesFragment extends Fragment implements BackPressedHandler {
    private static final String ARG_LAST_SEARCHED_TEXT = "lastSearchedText";
    private static final String ARG_SEARCH_CURRENT_PANEL = "searchCurrentPanel";

    private static final int PANEL_RECENT_SEARCHES = 0;
    private static final int PANEL_SEARCH_RESULTS = 1;

    private WikipediaApp app;
    private SearchView searchView;
    private EditText searchEditText;
    private SearchFunnel funnel;
    public SearchFunnel getFunnel() {
        return funnel;
    }

    /**
     * Whether the Search fragment is currently showing.
     */
    private boolean isSearchActive = false;

    /**
     * The last search term that the user entered. This will be passed into
     * the TitleSearch and FullSearch sub-fragments.
     */
    private String lastSearchedText;

    /**
     * View that contains the whole Search fragment. This is what should be shown/hidden when
     * the search is called for from the main activity.
     */
    private View searchContainerView;

    private RecentSearchesFragment recentSearchesFragment;
    private SearchResultsFragment searchResultsFragment;

    public SearchArticlesFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = WikipediaApp.getInstance();
        funnel = new SearchFunnel(WikipediaApp.getInstance());
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        app = (WikipediaApp) getActivity().getApplicationContext();
        app.getBus().register(this);
        View parentLayout = inflater.inflate(R.layout.fragment_search, container, false);

        searchContainerView = parentLayout.findViewById(R.id.search_container);
        searchContainerView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // Give the root container view an empty click handler, so that click events won't
                // get passed down to any underlying views (e.g. a PageViewFragment on top of which
                // this fragment is shown)
            }
        });

        View deleteButton = parentLayout.findViewById(R.id.recent_searches_delete_button);
        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AlertDialog.Builder alert = new AlertDialog.Builder(getActivity());
                alert.setMessage(getString(R.string.clear_recent_searches_confirm));
                alert.setPositiveButton(getString(R.string.yes), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        new DeleteAllRecentSearchesTask(app).execute();
                    }
                });
                alert.setNegativeButton(getString(R.string.no), null);
                alert.create().show();
            }
        });
        app.adjustDrawableToTheme(((ImageView)deleteButton).getDrawable());

        recentSearchesFragment = (RecentSearchesFragment)getChildFragmentManager().findFragmentById(R.id.search_panel_recent);

        searchResultsFragment = (SearchResultsFragment)getChildFragmentManager().findFragmentById(R.id.fragment_search_results);

        // make sure we're hidden by default
        searchContainerView.setVisibility(View.GONE);

        if (savedInstanceState != null) {
            lastSearchedText = savedInstanceState.getString(ARG_LAST_SEARCHED_TEXT);
            showPanel(savedInstanceState.getInt(ARG_SEARCH_CURRENT_PANEL));
        }
        return parentLayout;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        app.getBus().unregister(this);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(ARG_LAST_SEARCHED_TEXT, lastSearchedText);
        outState.putInt(ARG_SEARCH_CURRENT_PANEL, getActivePanel());
    }

    public void switchToSearch(String queryText) {
        startSearch(queryText, true);
        searchView.setQuery(queryText, false);
    }

    /**
     * Changes the search text box to contain a different string.
     * @param text The text you want to make the search box display.
     */
    public void setSearchText(String text) {
        searchView.setQuery(text, false);
    }

    /**
     * Show a particular panel, which can be one of:
     * - PANEL_RECENT_SEARCHES
     * - PANEL_SEARCH_RESULTS
     * Automatically hides the previous panel.
     * @param panel Which panel to show.
     */
    private void showPanel(int panel) {
        switch (panel) {
            case PANEL_RECENT_SEARCHES:
                searchResultsFragment.hide();
                recentSearchesFragment.show();
                break;
            case PANEL_SEARCH_RESULTS:
                recentSearchesFragment.hide();
                searchResultsFragment.show();
                break;
            default:
                break;
        }
    }

    private int getActivePanel() {
        if (searchResultsFragment.isShowing()) {
            return PANEL_SEARCH_RESULTS;
        } else {
            //otherwise, the recent searches must be showing:
            return PANEL_RECENT_SEARCHES;
        }
    }

    @Subscribe
    public void onWikipediaZeroStateChangeEvent(WikipediaZeroStateChangeEvent event) {
        updateZeroChrome();
    }

    /**
     * Kick off a search, based on a given search term. Will automatically pass the search to
     * Title search or Full search, based on which one is currently displayed.
     * If the search term is empty, the "recent searches" view will be shown.
     * @param term Phrase to search for.
     * @param force Whether to "force" starting this search. If the search is not forced, the
     *              search may be delayed by a small time, so that network requests are not sent
     *              too often.  If the search is forced, the network request is sent immediately.
     */
    public void startSearch(String term, boolean force) {
        if (!isSearchActive) {
            openSearch();
        }

        if (TextUtils.isEmpty(term)) {
            showPanel(PANEL_RECENT_SEARCHES);
        } else if (getActivePanel() == PANEL_RECENT_SEARCHES) {
            //start with title search...
            showPanel(PANEL_SEARCH_RESULTS);
        }

        lastSearchedText = term;

        searchResultsFragment.startSearch(term, force);
    }

    /**
     * Activate the Search fragment.
     */
    public void openSearch() {
        // create a new funnel every time Search is opened, to get a new session ID
        funnel = new SearchFunnel(WikipediaApp.getInstance());
        funnel.searchStart();
        isSearchActive = true;
        // invalidate our activity's ActionBar, so that all action items are removed, and
        // we can fill up the whole width of the ActionBar with our SearchView.
        getActivity().supportInvalidateOptionsMenu();
        setSearchViewEnabled(true);
        ((PageActivity) getActivity()).getDrawerToggle().setDrawerIndicatorEnabled(false);
        // show ourselves
        searchContainerView.setVisibility(View.VISIBLE);

        // if the current search string is empty, then it's a fresh start, so we'll show
        // recent searches by default. Otherwise, the currently-selected panel should already
        // be visible, so we don't need to do anything.
        if (TextUtils.isEmpty(lastSearchedText)) {
            showPanel(PANEL_RECENT_SEARCHES);
        }
    }

    public void closeSearch() {
        isSearchActive = false;
        // invalidate our activity's ActionBar, so that the original action items are restored.
        getActivity().supportInvalidateOptionsMenu();
        setSearchViewEnabled(false);
        ((PageActivity) getActivity()).getDrawerToggle().setDrawerIndicatorEnabled(true);
        // hide ourselves
        searchContainerView.setVisibility(View.GONE);
        Utils.hideSoftKeyboard(getActivity());
        addRecentSearch(lastSearchedText);
    }

    /**
     * Determine whether the Search fragment is currently active.
     * @return Whether the Search fragment is active.
     */
    public boolean isSearchActive() {
        return isSearchActive;
    }

    public boolean onBackPressed() {
        if (isSearchActive) {
            closeSearch();
            funnel.searchCancel();
            return true;
        }
        return false;
    }

    private void setSearchViewEnabled(boolean enabled) {
        View searchButton = getActivity().findViewById(R.id.main_search_bar);
        if (enabled) {
            // set up the SearchView...
            if (searchView == null) {
                searchView = (SearchView)getActivity().findViewById(R.id.main_search_view);
                searchView.setOnQueryTextListener(searchQueryListener);
                searchView.setOnCloseListener(searchCloseListener);

                searchEditText = (EditText) searchView
                        .findViewById(android.support.v7.appcompat.R.id.search_src_text);
                // need to explicitly set text color (you're welcome, 2.3!).
                searchEditText.setTextColor(getResources().getColor(
                        Utils.getThemedAttributeId(getActivity(), R.attr.edit_text_color)));
                // and make the text size be the same as the size of the search field
                // placeholder in the main activity
                searchEditText.setTextSize(TypedValue.COMPLEX_UNIT_PX, ((TextView) getActivity()
                        .findViewById(R.id.main_search_bar_text)).getTextSize());
                // reset its background
                searchEditText.setBackgroundColor(Color.TRANSPARENT);
                // make the search frame match_parent
                View searchEditFrame = searchView
                        .findViewById(android.support.v7.appcompat.R.id.search_edit_frame);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                searchEditFrame.setLayoutParams(params);
                // center the search text in it
                searchEditText.setGravity(Gravity.CENTER_VERTICAL);
                // and make the background of the search plate the same as our placeholder...
                View searchPlate = searchView
                        .findViewById(android.support.v7.appcompat.R.id.search_plate);
                searchPlate.setBackgroundResource(Utils.getThemedAttributeId(getActivity(), R.attr.search_bar_shape));
            }

            updateZeroChrome();
            searchView.setIconified(false);
            searchView.requestFocusFromTouch();

            // if we already have a previous search query, then put it into the SearchView, and it will
            // automatically trigger the showing of the corresponding search results.
            if (isValidQuery(lastSearchedText)) {
                searchView.setQuery(lastSearchedText, false);
                // automatically select all text in the search field, so that typing a new character
                // will clear it by default
                if (searchEditText != null) {
                    searchEditText.selectAll();
                }
            }
            searchButton.setVisibility(View.GONE);
            searchView.setVisibility(View.VISIBLE);
        } else {
            searchView.setVisibility(View.GONE);
            searchButton.setVisibility(View.VISIBLE);
        }
    }

    /*
    Update any UI elements related to WP Zero
     */
    private void updateZeroChrome() {
        if (searchEditText != null) {
            // setting the hint directly on the search EditText (instead of the SearchView)
            // gets rid of the magnify icon, which we don't want.
            searchEditText.setHint(app.getWikipediaZeroHandler().isZeroEnabled() ? getString(
                    R.string.zero_search_hint) : getString(R.string.search_hint));
        }
    }

    private boolean isValidQuery(String queryText) {
        return queryText != null && TextUtils.getTrimmedLength(queryText) > 0;
    }

    private final SearchView.OnQueryTextListener searchQueryListener = new SearchView.OnQueryTextListener() {
        @Override
        public boolean onQueryTextSubmit(String queryText) {
            PageTitle firstResult = null;
            if (getActivePanel() == PANEL_SEARCH_RESULTS) {
                firstResult = searchResultsFragment.getFirstResult();
            }
            if (firstResult != null) {
                navigateToTitle(firstResult);
                closeSearch();
            }
            return true;
        }

        @Override
        public boolean onQueryTextChange(String queryText) {
            startSearch(queryText.trim(), false);
            return true;
        }
    };

    private final SearchView.OnCloseListener searchCloseListener = new SearchView.OnCloseListener() {
        @Override
        public boolean onClose() {
            closeSearch();
            funnel.searchCancel();
            return false;
        }
    };

    public void navigateToTitle(PageTitle title) {
        if (!isAdded()) {
            return;
        }
        funnel.searchClick();
        HistoryEntry historyEntry = new HistoryEntry(title, HistoryEntry.SOURCE_SEARCH);
        Utils.hideSoftKeyboard(getActivity());
        closeSearch();
        ((PageActivity)getActivity()).displayNewPage(title, historyEntry);
    }

    private void addRecentSearch(String title) {
        if (isValidQuery(title)) {
            new SaveRecentSearchTask(new RecentSearch(title)).execute();
        }
    }

    private final class SaveRecentSearchTask extends SaneAsyncTask<Void> {
        private final RecentSearch entry;
        public SaveRecentSearchTask(RecentSearch entry) {
            super(SINGLE_THREAD);
            this.entry = entry;
        }

        @Override
        public Void performTask() throws Throwable {
            app.getPersister(RecentSearch.class).upsert(entry);
            return null;
        }

        @Override
        public void onFinish(Void result) {
            super.onFinish(result);
            recentSearchesFragment.updateList();
        }

        @Override
        public void onCatch(Throwable caught) {
            Log.w("SaveRecentSearchTask", "Caught " + caught.getMessage(), caught);
        }
    }
}
