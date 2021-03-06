package com.mapbox.services.android.navigation.ui.v5.instruction;

import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.Observer;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.annotation.Nullable;
import android.support.constraint.ConstraintLayout;
import android.support.constraint.ConstraintSet;
import android.support.transition.AutoTransition;
import android.support.transition.TransitionManager;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v4.widget.TextViewCompat;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.mapbox.api.directions.v5.models.BannerInstructions;
import com.mapbox.api.directions.v5.models.BannerText;
import com.mapbox.api.directions.v5.models.IntersectionLanes;
import com.mapbox.api.directions.v5.models.LegStep;
import com.mapbox.services.android.navigation.ui.v5.FeedbackButton;
import com.mapbox.services.android.navigation.ui.v5.NavigationButton;
import com.mapbox.services.android.navigation.ui.v5.NavigationViewModel;
import com.mapbox.services.android.navigation.ui.v5.R;
import com.mapbox.services.android.navigation.ui.v5.SoundButton;
import com.mapbox.services.android.navigation.ui.v5.ThemeSwitcher;
import com.mapbox.services.android.navigation.ui.v5.feedback.FeedbackBottomSheet;
import com.mapbox.services.android.navigation.ui.v5.feedback.FeedbackBottomSheetListener;
import com.mapbox.services.android.navigation.ui.v5.feedback.FeedbackItem;
import com.mapbox.services.android.navigation.ui.v5.instruction.maneuver.ManeuverView;
import com.mapbox.services.android.navigation.ui.v5.instruction.turnlane.TurnLaneAdapter;
import com.mapbox.services.android.navigation.ui.v5.listeners.InstructionListListener;
import com.mapbox.services.android.navigation.ui.v5.summary.list.InstructionListAdapter;
import com.mapbox.services.android.navigation.v5.milestone.BannerInstructionMilestone;
import com.mapbox.services.android.navigation.v5.milestone.Milestone;
import com.mapbox.services.android.navigation.v5.navigation.NavigationConstants;
import com.mapbox.services.android.navigation.v5.navigation.metrics.FeedbackEvent;
import com.mapbox.services.android.navigation.v5.offroute.OffRouteListener;
import com.mapbox.services.android.navigation.v5.routeprogress.ProgressChangeListener;
import com.mapbox.services.android.navigation.v5.routeprogress.RouteProgress;
import com.mapbox.services.android.navigation.v5.utils.DistanceFormatter;
import com.mapbox.services.android.navigation.v5.utils.LocaleUtils;
import com.mapbox.services.android.navigation.v5.utils.RouteUtils;

import java.util.List;

import timber.log.Timber;

/**
 * A view that can be used to display upcoming maneuver information and control
 * voice instruction mute / unmute.
 * <p>
 * An {@link ImageView} is used to display the maneuver image on the left.
 * Two {@link TextView}s are used to display distance to the next maneuver, as well
 * as the name of the destination / maneuver name / instruction based on what data is available
 * <p>
 * To automatically have this view update with information from
 * {@link com.mapbox.services.android.navigation.v5.navigation.MapboxNavigation},
 * add the view as a {@link ProgressChangeListener} and / or {@link OffRouteListener}
 *
 * @since 0.6.0
 */
public class InstructionView extends RelativeLayout implements FeedbackBottomSheetListener {

  private static final double VALID_DURATION_REMAINING = 70d;

  private ManeuverView upcomingManeuverView;
  private TextView upcomingDistanceText;
  private TextView upcomingPrimaryText;
  private TextView upcomingSecondaryText;
  private ManeuverView thenManeuverView;
  private TextView thenStepText;
  private NavigationAlertView alertView;
  private View rerouteLayout;
  private View turnLaneLayout;
  private View thenStepLayout;
  private RecyclerView rvTurnLanes;
  private RecyclerView rvInstructions;
  private TurnLaneAdapter turnLaneAdapter;
  private ConstraintLayout instructionLayout;
  private LinearLayout instructionLayoutText;
  private View instructionListLayout;
  private InstructionListAdapter instructionListAdapter;
  private Animation rerouteSlideUpTop;
  private Animation rerouteSlideDownTop;
  private LegStep currentStep;
  private NavigationViewModel navigationViewModel;
  private InstructionListListener instructionListListener;

  private DistanceFormatter distanceFormatter;
  private boolean isRerouting;
  private SoundButton soundButton;
  private FeedbackButton feedbackButton;

  public InstructionView(Context context) {
    this(context, null);
  }

  public InstructionView(Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, -1);
  }

  public InstructionView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  public void setInstructionListListener(InstructionListListener instructionListListener) {
    this.instructionListListener = instructionListListener;
  }

  /**
   * Once this view has finished inflating, it will bind the views.
   * <p>
   * It will also initialize the {@link RecyclerView} used to display the turn lanes
   * and animations used to show / hide views.
   */
  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();
    bind();
    initializeBackground();
    initializeTurnLaneRecyclerView();
    initializeInstructionListRecyclerView();
    initializeAnimations();
    initializeStepListClickListener();
    initializeButtons();
    ImageCoordinator.getInstance().initialize(getContext());
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    addBottomSheetListener();
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    cancelDelayedTransition();
  }

  @Override
  public void onFeedbackSelected(FeedbackItem feedbackItem) {
    navigationViewModel.updateFeedback(feedbackItem);
    alertView.showFeedbackSubmitted();
  }

  @Override
  public void onFeedbackDismissed() {
    navigationViewModel.cancelFeedback();
  }

  /**
   * Subscribes to a {@link NavigationViewModel} for
   * updates from {@link android.arch.lifecycle.LiveData}.
   * <p>
   * Updates all views with fresh data / shows &amp; hides re-route state.
   *
   * @param navigationViewModel to which this View is subscribing
   * @since 0.6.2
   */
  public void subscribe(NavigationViewModel navigationViewModel) {
    this.navigationViewModel = navigationViewModel;
    LifecycleOwner owner = (LifecycleOwner) getContext();
    navigationViewModel.instructionModel.observe(owner, new Observer<InstructionModel>() {
      @Override
      public void onChanged(@Nullable InstructionModel model) {
        if (model != null) {
          updateDataFromInstruction(model);
        }
      }
    });
    navigationViewModel.bannerInstructionModel.observe(owner, new Observer<BannerInstructionModel>() {
      @Override
      public void onChanged(@Nullable BannerInstructionModel model) {
        if (model != null) {
          updateManeuverView(model.getManeuverType(), model.getManeuverModifier(), model.getRoundaboutAngle());
          updateDataFromBannerText(model.getPrimaryBannerText(), model.getSecondaryBannerText());
        }
      }
    });
    navigationViewModel.isOffRoute.observe(owner, new Observer<Boolean>() {
      @Override
      public void onChanged(@Nullable Boolean isOffRoute) {
        if (isOffRoute != null) {
          if (isOffRoute) {
            showRerouteState();
          } else if (isRerouting) {
            hideRerouteState();
            alertView.showReportProblem();
          }
          isRerouting = isOffRoute;
        }
      }
    });
    subscribeAlertView();
    initializeButtonListeners();
    showButtons();
  }

  /**
   * Called in {@link ProgressChangeListener}, creates a new model and then
   * uses it to update the views.
   *
   * @param routeProgress used to provide navigation / routeProgress data
   * @since 0.6.2
   * @deprecated As of 0.20.0, use a combination of {@link InstructionView#updateDistanceWith(RouteProgress)} and
   * {@link InstructionView#updateBannerInstructionsWith(Milestone)} to achieve the same behavior.
   */
  @Deprecated
  public void update(RouteProgress routeProgress) {
    if (routeProgress != null && !isRerouting) {
      InstructionModel model = new InstructionModel(distanceFormatter, routeProgress);
      updateDataFromInstruction(model);
      updateManeuverView(model.getManeuverType(), model.getManeuverModifier(), model.getRoundaboutAngle());
      updateDataFromBannerText(model.getPrimaryBannerText(), model.getSecondaryBannerText());
    }
  }

  /**
   * Use this method inside a {@link ProgressChangeListener} to update this view with all other information
   * that is not updated by the {@link InstructionView#updateBannerInstructionsWith(Milestone)}.
   * <p>
   * This includes the distance remaining, instruction list, turn lanes, and next step information.
   *
   * @param routeProgress for route data used to populate the views
   * @since 0.20.0
   */
  public void updateDistanceWith(RouteProgress routeProgress) {
    if (routeProgress != null && !isRerouting) {
      InstructionModel model = new InstructionModel(distanceFormatter, routeProgress);
      updateDataFromInstruction(model);
    }
  }

  /**
   * Use this in a {@link com.mapbox.services.android.navigation.v5.milestone.MilestoneEventListener} to update
   * this view with new banner instructions.
   * <p>
   * This method will look at the type of milestone to determine when
   * it should update.
   *
   * @param milestone for retrieving the new BannerInstructions
   * @since 0.20.0
   */
  public void updateBannerInstructionsWith(Milestone milestone) {
    if (milestone instanceof BannerInstructionMilestone) {
      BannerInstructions instructions = ((BannerInstructionMilestone) milestone).getBannerInstructions();
      if (instructions == null || instructions.primary() == null) {
        return;
      }
      BannerText primary = instructions.primary();
      updateManeuverView(primary.type(), primary.modifier(), extractRoundaboutDegreesFrom(primary));
      updateDataFromBannerText(primary, instructions.secondary());
    }
  }

  /**
   * Shows {@link FeedbackBottomSheet} and adds a listener so
   * the proper feedback information is collected or the user dismisses the UI.
   */
  public void showFeedbackBottomSheet() {
    FragmentManager fragmentManager = obtainSupportFragmentManager();
    if (fragmentManager != null) {
      long duration = NavigationConstants.FEEDBACK_BOTTOM_SHEET_DURATION;
      FeedbackBottomSheet.newInstance(this, duration).show(fragmentManager, FeedbackBottomSheet.TAG);
    }
  }


  /**
   * Will slide the reroute view down from the top of the screen
   * and make it visible
   *
   * @since 0.6.0
   */
  public void showRerouteState() {
    if (rerouteLayout.getVisibility() == INVISIBLE) {
      rerouteLayout.startAnimation(rerouteSlideDownTop);
      rerouteLayout.setVisibility(VISIBLE);
    }
  }

  /**
   * Will slide the reroute view up to the top of the screen
   * and hide it
   *
   * @since 0.6.0
   */
  public void hideRerouteState() {
    if (rerouteLayout.getVisibility() == VISIBLE) {
      rerouteLayout.startAnimation(rerouteSlideUpTop);
      rerouteLayout.setVisibility(INVISIBLE);
    }
  }

  /**
   * Can be used to determine the visibility of the instruction list.
   *
   * @return true if instruction list is visible, false is not
   */
  public boolean isShowingInstructionList() {
    return instructionListLayout.getVisibility() == VISIBLE;
  }

  /**
   * Hide the instruction list.
   * <p>
   * This is based on orientation so the different layouts (for portrait vs. landscape)
   * can be animated appropriately.
   */
  public void hideInstructionList() {
    rvInstructions.stopScroll();
    beginDelayedTransition();
    int orientation = getContext().getResources().getConfiguration().orientation;
    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
      updateLandscapeConstraintsTo(R.layout.instruction_layout);
    }
    instructionListLayout.setVisibility(GONE);
    onInstructionListVisibilityChanged(false);
  }

  /**
   * Show the instruction list.
   * <p>
   * This is based on orientation so the different layouts (for portrait vs. landscape)
   * can be animated appropriately.
   */
  public void showInstructionList() {
    onInstructionListVisibilityChanged(true);
    instructionLayout.requestFocus();
    beginDelayedListTransition();
    int orientation = getContext().getResources().getConfiguration().orientation;
    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
      updateLandscapeConstraintsTo(R.layout.instruction_layout_alt);
    }
    instructionListLayout.setVisibility(VISIBLE);
  }

  public boolean handleBackPressed() {
    if (isShowingInstructionList()) {
      hideInstructionList();
      return true;
    }
    return false;
  }

  /**
   * Sets the distance formatter
   *
   * @param distanceFormatter to set
   */
  public void setDistanceFormatter(DistanceFormatter distanceFormatter) {
    if (distanceFormatter != null && !distanceFormatter.equals(this.distanceFormatter)) {
      this.distanceFormatter = distanceFormatter;
      instructionListAdapter.updateDistanceFormatter(distanceFormatter);
    }
  }

  /**
   * Gets the sound button which is used for muting/unmuting, for uses such as adding listeners and
   * hiding the button.
   *
   * @return sound button with {@link NavigationButton} API
   */
  public NavigationButton retrieveSoundButton() {
    return soundButton;
  }

  /**
   * Gets the feedback button which is used for sending feedback, for uses such as adding listeners
   * and hiding the button.
   *
   * @return feedback button with {@link NavigationButton} API
   */
  public NavigationButton retrieveFeedbackButton() {
    return feedbackButton;
  }

  /**
   * Inflates this layout needed for this view and initializes the locale as the device locale.
   */
  private void initialize() {
    LocaleUtils localeUtils = new LocaleUtils();
    String language = localeUtils.inferDeviceLanguage(getContext());
    String unitType = localeUtils.getUnitTypeForDeviceLocale(getContext());
    int roundingIncrement = NavigationConstants.ROUNDING_INCREMENT_FIFTY;
    distanceFormatter = new DistanceFormatter(getContext(), language, unitType, roundingIncrement);
    inflate(getContext(), R.layout.instruction_view_layout, this);
  }

  /**
   * Finds and binds all necessary views
   */
  private void bind() {
    upcomingManeuverView = findViewById(R.id.maneuverView);
    upcomingDistanceText = findViewById(R.id.stepDistanceText);
    upcomingPrimaryText = findViewById(R.id.stepPrimaryText);
    upcomingSecondaryText = findViewById(R.id.stepSecondaryText);
    thenManeuverView = findViewById(R.id.thenManeuverView);
    thenStepText = findViewById(R.id.thenStepText);
    alertView = findViewById(R.id.alertView);
    rerouteLayout = findViewById(R.id.rerouteLayout);
    turnLaneLayout = findViewById(R.id.turnLaneLayout);
    thenStepLayout = findViewById(R.id.thenStepLayout);
    rvTurnLanes = findViewById(R.id.rvTurnLanes);
    instructionLayout = findViewById(R.id.instructionLayout);
    instructionLayoutText = findViewById(R.id.instructionLayoutText);
    instructionListLayout = findViewById(R.id.instructionListLayout);
    rvInstructions = findViewById(R.id.rvInstructions);
    soundButton = findViewById(R.id.soundLayout);
    feedbackButton = findViewById(R.id.feedbackLayout);
    initializeInstructionAutoSize();
  }

  /**
   * For API 21 and lower, manually set the drawable tint based on the colors
   * set in the given navigation theme (light or dark).
   */
  private void initializeBackground() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
      int navigationViewBannerBackgroundColor = ThemeSwitcher.retrieveThemeColor(getContext(),
        R.attr.navigationViewBannerBackground);
      int navigationViewListBackgroundColor = ThemeSwitcher.retrieveThemeColor(getContext(),
        R.attr.navigationViewListBackground);
      // Instruction Layout landscape - banner background
      if (getContext().getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
        View instructionLayoutManeuver = findViewById(R.id.instructionManeuverLayout);
        Drawable maneuverBackground = DrawableCompat.wrap(instructionLayoutManeuver.getBackground()).mutate();
        DrawableCompat.setTint(maneuverBackground, navigationViewBannerBackgroundColor);

        View thenStepLayout = findViewById(R.id.thenStepLayout);
        Drawable thenStepBackground = DrawableCompat.wrap(thenStepLayout.getBackground()).mutate();
        DrawableCompat.setTint(thenStepBackground, navigationViewListBackgroundColor);

        View turnLaneLayout = findViewById(R.id.turnLaneLayout);
        Drawable turnLaneBackground = DrawableCompat.wrap(turnLaneLayout.getBackground()).mutate();
        DrawableCompat.setTint(turnLaneBackground, navigationViewListBackgroundColor);
      }
    }
  }

  /**
   * Called after we bind the views, this will allow the step instruction {@link TextView}
   * to automatically re-size based on the length of the text.
   */
  private void initializeInstructionAutoSize() {
    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(upcomingPrimaryText,
      26, 30, 1, TypedValue.COMPLEX_UNIT_SP);
    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(upcomingSecondaryText,
      20, 26, 1, TypedValue.COMPLEX_UNIT_SP);
  }

  /**
   * Sets up the {@link RecyclerView} that is used to display the turn lanes.
   */
  private void initializeTurnLaneRecyclerView() {
    turnLaneAdapter = new TurnLaneAdapter();
    rvTurnLanes.setAdapter(turnLaneAdapter);
    rvTurnLanes.setHasFixedSize(true);
    rvTurnLanes.setLayoutManager(new LinearLayoutManager(getContext(),
      LinearLayoutManager.HORIZONTAL, false));
  }

  /**
   * Sets up the {@link RecyclerView} that is used to display the list of instructions.
   */
  private void initializeInstructionListRecyclerView() {
    RouteUtils routeUtils = new RouteUtils();
    instructionListAdapter = new InstructionListAdapter(routeUtils, distanceFormatter);
    rvInstructions.setAdapter(instructionListAdapter);
    rvInstructions.setHasFixedSize(true);
    rvInstructions.setLayoutManager(new LinearLayoutManager(getContext()));
  }

  /**
   * Initializes all animations needed to show / hide views.
   */
  private void initializeAnimations() {
    Context context = getContext();
    rerouteSlideDownTop = AnimationUtils.loadAnimation(context, R.anim.slide_down_top);
    rerouteSlideUpTop = AnimationUtils.loadAnimation(context, R.anim.slide_up_top);
  }

  private void onInstructionListVisibilityChanged(boolean visible) {
    if (instructionListListener != null) {
      instructionListListener.onInstructionListVisibilityChanged(visible);
    }
  }

  private void addBottomSheetListener() {
    FragmentManager fragmentManager = obtainSupportFragmentManager();
    if (fragmentManager != null) {
      String tag = FeedbackBottomSheet.TAG;
      FeedbackBottomSheet feedbackBottomSheet = (FeedbackBottomSheet) fragmentManager.findFragmentByTag(tag);
      if (feedbackBottomSheet != null) {
        feedbackBottomSheet.setFeedbackBottomSheetListener(this);
      }
    }
  }

  private void subscribeAlertView() {
    alertView.subscribe(navigationViewModel);
  }

  private void initializeButtonListeners() {
    feedbackButton.addOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View view) {
        navigationViewModel.recordFeedback(FeedbackEvent.FEEDBACK_SOURCE_UI);
        showFeedbackBottomSheet();
      }
    });
    soundButton.addOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View view) {
        navigationViewModel.setMuted(soundButton.toggleMute());
      }
    });
  }

  private void showButtons() {
    feedbackButton.show();
    soundButton.show();
  }

  private void initializeStepListClickListener() {
    int deviceOrientation = getContext().getResources().getConfiguration().orientation;
    boolean isOrientationLandscape = deviceOrientation == Configuration.ORIENTATION_LANDSCAPE;
    if (isOrientationLandscape) {
      initializeLandscapeListListener();
    } else {
      initializePortraitListListener();
    }
  }

  private void initializeButtons() {
    feedbackButton.hide();
    soundButton.hide();
  }

  /**
   * For portrait orientation, attach the listener to the whole layout
   * and use custom animations to hide and show the instructions /sound layout
   */
  private void initializePortraitListListener() {
    instructionLayout.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View instructionView) {
        boolean instructionsVisible = instructionListLayout.getVisibility() == VISIBLE;
        if (!instructionsVisible) {
          showInstructionList();
        } else {
          hideInstructionList();
        }
      }
    });
  }

  /**
   * For landscape orientation, the click listener is attached to
   * the instruction text layout and the constraints are adjusted before animating
   */
  private void initializeLandscapeListListener() {
    instructionLayoutText.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View instructionLayoutText) {
        boolean instructionsVisible = instructionListLayout.getVisibility() == VISIBLE;
        if (!instructionsVisible) {
          showInstructionList();
        } else {
          hideInstructionList();
        }
      }
    });
  }

  /**
   * Looks to see if we have a new distance text.
   *
   * @param model provides distance text
   */
  private boolean newDistanceText(InstructionModel model) {
    return !upcomingDistanceText.getText().toString().isEmpty()
      && !TextUtils.isEmpty(model.getStepResources().getStepDistanceRemaining())
      && !upcomingDistanceText.getText().toString()
      .contentEquals(model.getStepResources().getStepDistanceRemaining().toString());
  }

  /**
   * Sets current distance text.
   *
   * @param model provides distance text
   */
  private void distanceText(InstructionModel model) {
    upcomingDistanceText.setText(model.getStepResources().getStepDistanceRemaining());
  }

  private InstructionLoader createInstructionLoader(TextView textView, BannerText bannerText) {
    if (hasComponents(bannerText)) {
      return new InstructionLoader(textView, bannerText.components());
    } else {
      return null;
    }
  }

  private boolean hasComponents(BannerText bannerText) {
    return bannerText != null && bannerText.components() != null && !bannerText.components().isEmpty();
  }

  /**
   * Looks to see if we have a new step.
   *
   * @param routeProgress provides updated step information
   * @return true if new step, false if not
   */
  private boolean newStep(RouteProgress routeProgress) {
    boolean newStep = currentStep == null || !currentStep.equals(routeProgress.currentLegProgress().currentStep());
    currentStep = routeProgress.currentLegProgress().currentStep();
    return newStep;
  }

  /**
   * Looks for turn lane data and populates / shows the turn lane view if found.
   * If not, hides the turn lane view.
   *
   * @param model created with new {@link RouteProgress} holding turn lane data
   */
  private void updateTurnLanes(InstructionModel model) {
    List<IntersectionLanes> turnLanes = model.getStepResources().getTurnLanes();
    String maneuverViewModifier = model.getManeuverModifier();
    double durationRemaining = model.getProgress().currentLegProgress().currentStepProgress().durationRemaining();

    if (shouldShowTurnLanes(turnLanes, maneuverViewModifier, durationRemaining)) {
      if (turnLaneLayout.getVisibility() == GONE) {
        turnLaneAdapter.addTurnLanes(turnLanes, maneuverViewModifier);
        showTurnLanes();
      }
    } else {
      hideTurnLanes();
    }
  }

  private boolean shouldShowTurnLanes(List<IntersectionLanes> turnLanes,
                                      String maneuverViewModifier, double durationRemaining) {
    return turnLanes != null && !TextUtils.isEmpty(maneuverViewModifier)
      && durationRemaining <= VALID_DURATION_REMAINING;
  }

  /**
   * Shows turn lane view
   */
  private void showTurnLanes() {
    if (turnLaneLayout.getVisibility() == GONE) {
      beginDelayedTransition();
      turnLaneLayout.setVisibility(VISIBLE);
    }
  }

  /**
   * Hides turn lane view
   */
  private void hideTurnLanes() {
    if (turnLaneLayout.getVisibility() == VISIBLE) {
      beginDelayedTransition();
      turnLaneLayout.setVisibility(GONE);
    }
  }

  /**
   * Check if the the then step should be shown.
   * If true, update the "then" maneuver and the "then" step text.
   * If false, hide the then layout.
   *
   * @param model to determine if the then step layout should be shown
   */
  private void updateThenStep(InstructionModel model) {
    if (shouldShowThenStep(model)) {
      String thenStepManeuverType = model.getStepResources().getThenStepManeuverType();
      String thenStepManeuverModifier = model.getStepResources().getThenStepManeuverModifier();
      thenManeuverView.setManeuverTypeAndModifier(thenStepManeuverType, thenStepManeuverModifier);
      Float roundaboutAngle = model.getStepResources().getThenStepRoundaboutDegrees();
      if (roundaboutAngle != null) {
        thenManeuverView.setRoundaboutAngle(roundaboutAngle);
      }
      thenStepText.setText(model.getThenBannerText().text());
      showThenStepLayout();
    } else {
      hideThenStepLayout();
    }
  }

  /**
   * First, checks if the turn lanes are visible (if they are, don't show then step).
   * Second, checks if the upcoming step is less than 15 seconds long.
   * This is our cue to show the thenStep.
   *
   * @param model to check the upcoming step
   * @return true if should show, false if not
   */
  private boolean shouldShowThenStep(InstructionModel model) {
    return turnLaneLayout.getVisibility() != VISIBLE
      && model.getThenBannerText() != null
      && model.getStepResources().shouldShowThenStep();
  }

  /**
   * Shows then step layout
   */
  private void showThenStepLayout() {
    if (thenStepLayout.getVisibility() == GONE) {
      beginDelayedTransition();
      thenStepLayout.setVisibility(VISIBLE);
    }
  }

  /**
   * Hides then step layout
   */
  private void hideThenStepLayout() {
    if (thenStepLayout.getVisibility() == VISIBLE) {
      beginDelayedTransition();
      thenStepLayout.setVisibility(GONE);
    }
  }

  @Nullable
  private FragmentManager obtainSupportFragmentManager() {
    try {
      return ((FragmentActivity) getContext()).getSupportFragmentManager();
    } catch (ClassCastException exception) {
      Timber.e(exception);
      return null;
    }
  }

  /**
   * Adjust the banner text layout {@link ConstraintLayout} vertical bias.
   *
   * @param percentBias to be set to the text layout
   */
  private void adjustBannerTextVerticalBias(float percentBias) {
    int orientation = getContext().getResources().getConfiguration().orientation;
    if (orientation == Configuration.ORIENTATION_PORTRAIT) {
      ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) instructionLayoutText.getLayoutParams();
      params.verticalBias = percentBias;
      instructionLayoutText.setLayoutParams(params);
    }
  }

  private void beginDelayedTransition() {
    TransitionManager.beginDelayedTransition(this);
  }

  private void beginDelayedListTransition() {
    AutoTransition transition = new AutoTransition();
    transition.addListener(new InstructionListTransitionListener(rvInstructions, instructionListAdapter));
    TransitionManager.beginDelayedTransition(this, transition);
  }

  private void cancelDelayedTransition() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      clearAnimation();
    }
  }

  private void updateDataFromInstruction(InstructionModel model) {
    updateDistanceText(model);
    updateInstructionList(model);
    updateTurnLanes(model);
    updateThenStep(model);
    if (newStep(model.getProgress())) {
      LegStep upComingStep = model.getProgress().currentLegProgress().upComingStep();
      ImageCoordinator.getInstance().prefetchImageCache(upComingStep);
    }
  }

  /**
   * Looks to see if we have a new instruction text.
   * Sets new instruction text if found.
   */
  private void updateDataFromBannerText(BannerText primaryBannerText, BannerText secondaryBannerText) {
    if (primaryBannerText != null) {
      InstructionLoader instructionLoader = createInstructionLoader(upcomingPrimaryText, primaryBannerText);
      if (instructionLoader != null) {
        instructionLoader.loadInstruction();
      }
    }
    if (secondaryBannerText != null) {
      if (upcomingSecondaryText.getVisibility() == GONE) {
        upcomingSecondaryText.setVisibility(VISIBLE);
        upcomingPrimaryText.setMaxLines(1);
        adjustBannerTextVerticalBias(0.65f);
      }
      InstructionLoader instructionLoader = createInstructionLoader(upcomingSecondaryText, secondaryBannerText);
      if (instructionLoader != null) {
        instructionLoader.loadInstruction();
      }
    } else {
      upcomingPrimaryText.setMaxLines(2);
      upcomingSecondaryText.setVisibility(GONE);
      adjustBannerTextVerticalBias(0.5f);
    }
  }

  /**
   * Looks to see if we have a new maneuver modifier or type.
   * Updates new maneuver image if one is found.
   */
  private void updateManeuverView(String maneuverViewType, String maneuverViewModifier,
                                  @Nullable Float roundaboutAngle) {
    upcomingManeuverView.setManeuverTypeAndModifier(maneuverViewType, maneuverViewModifier);
    if (roundaboutAngle != null) {
      upcomingManeuverView.setRoundaboutAngle(roundaboutAngle);
    }
  }

  @Nullable
  private Float extractRoundaboutDegreesFrom(BannerText bannerText) {
    Double degrees = bannerText.degrees();
    if (degrees != null) {
      return degrees.floatValue();
    }
    return null;
  }

  /**
   * Looks to see if we have a new distance text.
   * Sets new distance text if found.
   *
   * @param model provides distance text
   */
  private void updateDistanceText(InstructionModel model) {
    if (newDistanceText(model)) {
      distanceText(model);
    } else if (upcomingDistanceText.getText().toString().isEmpty()) {
      distanceText(model);
    }
  }

  private void updateLandscapeConstraintsTo(int layoutRes) {
    ConstraintSet collapsed = new ConstraintSet();
    collapsed.clone(getContext(), layoutRes);
    collapsed.applyTo(instructionLayout);
  }

  /**
   * Used to update the instructions list with the current steps.
   *
   * @param model to provide the current steps and unit type
   */
  private void updateInstructionList(InstructionModel model) {
    RouteProgress routeProgress = model.getProgress();
    boolean isListShowing = instructionListLayout.getVisibility() == VISIBLE;
    instructionListAdapter.updateBannerListWith(routeProgress, isListShowing);
  }
}
