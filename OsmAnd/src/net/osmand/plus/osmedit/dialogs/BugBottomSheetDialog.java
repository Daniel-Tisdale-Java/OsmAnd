package net.osmand.plus.osmedit.dialogs;

import android.content.res.ColorStateList;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import net.osmand.AndroidUtils;
import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.UiUtilities;
import net.osmand.plus.base.MenuBottomSheetDialogFragment;
import net.osmand.plus.base.bottomsheetmenu.BaseBottomSheetItem;
import net.osmand.plus.base.bottomsheetmenu.simpleitems.DividerSpaceItem;
import net.osmand.plus.base.bottomsheetmenu.simpleitems.TitleItem;
import net.osmand.plus.osmedit.HandleOsmNoteAsyncTask;
import net.osmand.plus.osmedit.OsmBugsLayer;
import net.osmand.plus.osmedit.OsmBugsUtil;
import net.osmand.plus.osmedit.OsmNotesPoint;
import net.osmand.plus.osmedit.OsmPoint;

import org.apache.commons.logging.Log;

public class BugBottomSheetDialog extends MenuBottomSheetDialogFragment {

	public static final String TAG = BugBottomSheetDialog.class.getSimpleName();
	private static final Log LOG = PlatformUtil.getLog(BugBottomSheetDialog.class);

	private OsmBugsUtil osmBugsUtil;
	private OsmBugsUtil local;
	private String text;
	private int titleTextId;
	private int posButtonTextId;
	private OsmPoint.Action action;
	private OsmBugsLayer.OpenStreetNote bug;
	private OsmNotesPoint point;
	private HandleOsmNoteAsyncTask.HandleBugListener handleBugListener;
	private TextInputEditText noteText;

	@Override
	public void createMenuItems(Bundle savedInstanceState) {
		OsmandApplication app = getMyApplication();
		if (app == null) {
			return;
		}

		items.add(new TitleItem(getString(titleTextId)));

		View osmNoteView = View.inflate(UiUtilities.getThemedContext(app, nightMode),
				R.layout.open_osm_note_text, null);
		osmNoteView.getViewTreeObserver().addOnGlobalLayoutListener(getShadowLayoutListener());
		TextInputLayout textBox = osmNoteView.findViewById(R.id.name_text_box);
		textBox.setHint(AndroidUtils.addColon(app, R.string.osn_bug_name));
		ColorStateList colorStateList = ColorStateList.valueOf(ContextCompat
				.getColor(app, nightMode ? R.color.text_color_secondary_dark : R.color.text_color_secondary_light));
		textBox.setDefaultHintTextColor(colorStateList);
		noteText = osmNoteView.findViewById(R.id.name_edit_text);
		noteText.setText(text);

		BaseBottomSheetItem editOsmNote = new BaseBottomSheetItem.Builder()
				.setCustomView(osmNoteView)
				.create();
		items.add(editOsmNote);

		items.add(new DividerSpaceItem(app, app.getResources().getDimensionPixelSize(R.dimen.content_padding_small)));
	}

	@Override
	protected int getRightBottomButtonTextId() {
		return posButtonTextId;
	}

	@Override
	protected void onRightBottomButtonClick() {
		new HandleOsmNoteAsyncTask(osmBugsUtil, local, bug, point, noteText.getText().toString(), action,
				handleBugListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
		dismiss();
	}

	public static void showInstance(@NonNull FragmentManager fm, OsmBugsUtil osmBugsUtil, OsmBugsUtil local,
									String text, int titleTextId, int posButtonTextId, final OsmPoint.Action action,
									final OsmBugsLayer.OpenStreetNote bug, final OsmNotesPoint point,
									HandleOsmNoteAsyncTask.HandleBugListener handleBugListener) {
		try {
			if (!fm.isStateSaved()) {
				BugBottomSheetDialog fragment = new BugBottomSheetDialog();
				fragment.setRetainInstance(true);
				fragment.osmBugsUtil = osmBugsUtil;
				fragment.local = local;
				fragment.text = text;
				fragment.titleTextId = titleTextId;
				fragment.posButtonTextId = posButtonTextId;
				fragment.action = action;
				fragment.bug = bug;
				fragment.point = point;
				fragment.handleBugListener = handleBugListener;
				fragment.show(fm, TAG);
			}
		} catch (RuntimeException e) {
			LOG.error("showInstance", e);
		}
	}
}
