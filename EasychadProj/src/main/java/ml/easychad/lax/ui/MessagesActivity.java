/*
 * This is the source code of Telegram for Android v. 1.3.2.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013.
 */

package ml.easychad.lax.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import ml.easychad.lax.Lax;
import ml.easychad.lax.android.AndroidUtilities;
import ml.easychad.lax.android.ContactsController;
import ml.easychad.lax.android.LocaleController;
import ml.easychad.lax.android.MessagesController;
import ml.easychad.lax.android.MessagesStorage;
import ml.easychad.lax.android.NotificationCenter;
import ml.easychad.lax.messenger.FileLog;
import ml.easychad.lax.messenger.R;
import ml.easychad.lax.messenger.TLObject;
import ml.easychad.lax.messenger.TLRPC;
import ml.easychad.lax.messenger.UserConfig;
import ml.easychad.lax.messenger.Utilities;
import ml.easychad.lax.ui.Adapters.BaseContactsSearchAdapter;
import ml.easychad.lax.ui.Cells.ChatOrUserCell;
import ml.easychad.lax.ui.Cells.DialogCell;
import ml.easychad.lax.ui.Views.ActionBar.ActionBarLayer;
import ml.easychad.lax.ui.Views.ActionBar.ActionBarMenu;
import ml.easychad.lax.ui.Views.ActionBar.ActionBarMenuItem;
import ml.easychad.lax.ui.Views.ActionBar.BaseFragment;
import ml.easychad.lax.ui.Views.SettingsSectionLayout;

public class MessagesActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate,Lax {
    private ListView messagesListView;
    private MessagesAdapter messagesListViewAdapter;
    private TextView searchEmptyView;
    private View progressView;
    private View emptyView;
    private String selectAlertString;
    private String selectAlertStringGroup;
    private boolean serverOnly = false;

    private static boolean dialogsLoaded = false;
    private boolean searching = false;
    private boolean searchWas = false;
    private boolean onlySelect = false;
    private int activityToken = (int)(Utilities.random.nextDouble() * Integer.MAX_VALUE);
    private long selectedDialog;

    private MessagesActivityDelegate delegate;

    private long openedDialogId = 0;

    private final static int messages_list_menu_new_messages = 1;
    private final static int messages_list_menu_new_chat = 2;
    private final static int messages_list_menu_other = 6;
    private final static int messages_list_menu_new_secret_chat = 3;
    private final static int messages_list_menu_contacts = 4;
    private final static int messages_list_menu_settings = 5;
    private final static int messages_list_menu_new_broadcast = 6;
    private final static int messages_list_how_it_works = 7;
    private final static int messages_list_site_reg = 8;
    private final static int messages_list_payment = 9;

    public static interface MessagesActivityDelegate {
        public abstract void didSelectDialog(MessagesActivity fragment, long dialog_id, boolean param);
    }

    public MessagesActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.emojiDidLoaded);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.reloadSearchResults);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.encryptedChatUpdated);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.contactsDidLoaded);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.appDidLogout);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.openedChatChanged);
        if (getArguments() != null) {
            onlySelect = arguments.getBoolean("onlySelect", false);
            serverOnly = arguments.getBoolean("serverOnly", false);
            selectAlertString = arguments.getString("selectAlertString");
            selectAlertStringGroup = arguments.getString("selectAlertStringGroup");
        }
        if (!dialogsLoaded) {
            MessagesController.getInstance().loadDialogs(0, 0, 100, true);
            dialogsLoaded = true;
        }
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.emojiDidLoaded);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.reloadSearchResults);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.encryptedChatUpdated);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.contactsDidLoaded);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.appDidLogout);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.openedChatChanged);
        delegate = null;
    }

    @Override
    public View createView(LayoutInflater inflater, ViewGroup container) {
        if (fragmentView == null) {
            ActionBarMenu menu = actionBarLayer.createMenu();
            menu.addItem(0, R.drawable.ic_ab_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {

                @Override
                public void onSearchExpand() {
                    searching = true;
                    if (messagesListView != null) {
                        messagesListView.setEmptyView(searchEmptyView);
                    }
                    if (emptyView != null) {
                        emptyView.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onSearchCollapse() {
                    searching = false;
                    searchWas = false;
                    if (messagesListView != null) {
                        messagesListView.setEmptyView(emptyView);
                        searchEmptyView.setVisibility(View.GONE);
                    }
                    if (messagesListViewAdapter != null) {
                        messagesListViewAdapter.searchDialogs(null);
                    }
                }

                @Override
                public void onTextChanged(EditText editText) {
                    String text = editText.getText().toString();
                    if (messagesListViewAdapter != null) {
                        messagesListViewAdapter.searchDialogs(text);
                    }
                    if (text.length() != 0) {
                        searchWas = true;
                        if (messagesListViewAdapter != null) {
                            messagesListViewAdapter.notifyDataSetChanged();
                        }
                        if (searchEmptyView != null) {
                            messagesListView.setEmptyView(searchEmptyView);
                            emptyView.setVisibility(View.GONE);
                        }
                    }
                }
            });
            ActionBarMenuItem secretChat = menu.addItem(messages_list_menu_new_secret_chat,R.drawable.lock_chat);
            secretChat.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    //TODO: NEW SECRET CHAT IN ACTION BAR MESSAGES ACTIVITY
                    Bundle args = new Bundle();
                    args.putBoolean("onlyUsers", true);
                    args.putBoolean("destroyAfterSelect", true);
                    args.putBoolean("usersAsSections", true);
                    args.putBoolean("createSecretChat", true);
                    presentFragment(new ContactsActivity(args));
                }
            });
            if (onlySelect) {
                actionBarLayer.setDisplayHomeAsUpEnabled(true, R.drawable.ic_ab_back);
                actionBarLayer.setTitle(LocaleController.getString("SelectChat", R.string.SelectChat));


            } else {
                actionBarLayer.setDisplayUseLogoEnabled(true, R.drawable.ic_ab_logo);
                actionBarLayer.setTitle(LocaleController.getString("AppName", R.string.AppName));
                actionBarLayer.setTitleFont("fonts/Lobster-Regular.ttf");


                menu.addItem(messages_list_menu_new_messages, R.drawable.ic_ab_compose);
                ActionBarMenuItem item = menu.addItem(0, R.drawable.ic_ab_other);
                item.addSubItem(messages_list_menu_new_chat, LocaleController.getString("NewGroup", R.string.NewGroup), 0);
                //item.addSubItem(messages_list_menu_new_secret_chat, LocaleController.getString("NewSecretChat", R.string.NewSecretChat), 0);
//                item.addSubItem(messages_list_menu_new_broadcast, LocaleController.getString("NewBroadcastList", R.string.NewBroadcastList), 0);
               //TODO
                item.addSubItem(messages_list_menu_contacts, LocaleController.getString("Contacts", R.string.Contacts), 0);
                item.addSubItem(messages_list_menu_settings, LocaleController.getString("Settings", R.string.Settings), 0);
                item.addSubItem(messages_list_how_it_works, LocaleController.getString("", R.string.HowItWorks), 0);
                item.addSubItem(messages_list_site_reg, LocaleController.getString("", R.string.SiteRegistration), 0);
                item.addSubItem(messages_list_payment, LocaleController.getString("", R.string.Pay), 0);

            }
            actionBarLayer.setBackOverlay(R.layout.updating_state_layout);

            actionBarLayer.setActionBarMenuOnItemClick(new ActionBarLayer.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == messages_list_menu_settings) {
                        presentFragment(new SettingsActivity());
                    } else if (id == messages_list_menu_contacts) {
                        presentFragment(new ContactsActivity(null));
                    } else if (id == messages_list_menu_new_messages) {
                        Bundle args = new Bundle();
                        args.putBoolean("onlyUsers", true);
                        args.putBoolean("destroyAfterSelect", true);
                        args.putBoolean("usersAsSections", true);
                        presentFragment(new ContactsActivity(args));
                    } else if (id == messages_list_menu_new_secret_chat) {
                        Bundle args = new Bundle();
                        args.putBoolean("onlyUsers", true);
                        args.putBoolean("destroyAfterSelect", true);
                        args.putBoolean("usersAsSections", true);
                        args.putBoolean("createSecretChat", true);
                        presentFragment(new ContactsActivity(args));
                    } else if (id == messages_list_menu_new_chat) {
                        presentFragment(new GroupCreateActivity());
                    } else if (id == -1) {
                        if (onlySelect) {
                            finishFragment();
                        }
                    } else if (id == messages_list_menu_new_broadcast) {
                        Bundle args = new Bundle();
                        args.putBoolean("broadcast", true);
                        presentFragment(new GroupCreateActivity(args));
                    } else if (id == messages_list_how_it_works){
                        //TODO:
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(website));
                        getParentActivity().startActivity(i);
                    } else if (id == messages_list_site_reg){
                        //TODO:
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(website));
                        getParentActivity().startActivity(i);
                    } else if (id == messages_list_payment){
                        //TODO:
                        LaunchActivity la = (LaunchActivity) getParentActivity();
                        la.mHelper.launchPurchaseFlow(la,la.PREMIUM, la.RC_REQUEST,
                                la.mPurchaseFinishedListener,"");
                    }

                }
            });

            searching = false;
            searchWas = false;

            fragmentView = inflater.inflate(R.layout.messages_list, container, false);

            messagesListViewAdapter = new MessagesAdapter(getParentActivity());

            messagesListView = (ListView)fragmentView.findViewById(R.id.messages_list_view);
            messagesListView.setAdapter(messagesListViewAdapter);

            progressView = fragmentView.findViewById(R.id.progressLayout);
            messagesListViewAdapter.notifyDataSetChanged();
            searchEmptyView = (TextView)fragmentView.findViewById(R.id.searchEmptyView);
            searchEmptyView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return true;
                }
            });
            searchEmptyView.setText(LocaleController.getString("NoResult", R.string.NoResult));
            emptyView = fragmentView.findViewById(R.id.list_empty_view);
            emptyView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return true;
                }
            });
            TextView textView = (TextView)fragmentView.findViewById(R.id.list_empty_view_text1);
            textView.setText(LocaleController.getString("NoChats", R.string.NoChats));
            textView = (TextView)fragmentView.findViewById(R.id.list_empty_view_text2);
            textView.setText(LocaleController.getString("NoChats", R.string.NoChatsHelp));

            if (MessagesController.getInstance().loadingDialogs && MessagesController.getInstance().dialogs.isEmpty()) {
                messagesListView.setEmptyView(null);
                searchEmptyView.setVisibility(View.GONE);
                emptyView.setVisibility(View.GONE);
                progressView.setVisibility(View.VISIBLE);
            } else {
                if (searching && searchWas) {
                    messagesListView.setEmptyView(searchEmptyView);
                    emptyView.setVisibility(View.GONE);
                } else {
                    messagesListView.setEmptyView(emptyView);
                    searchEmptyView.setVisibility(View.GONE);
                }
                progressView.setVisibility(View.GONE);
            }

            messagesListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                    if (messagesListViewAdapter == null) {
                        return;
                    }
                    TLObject obj = messagesListViewAdapter.getItem(i);
                    if (obj == null) {
                        return;
                    }
                    long dialog_id = 0;
                    if (obj instanceof TLRPC.User) {
                        dialog_id = ((TLRPC.User) obj).id;
                        if (messagesListViewAdapter.isGlobalSearch(i)) {
                            ArrayList<TLRPC.User> users = new ArrayList<TLRPC.User>();
                            users.add((TLRPC.User)obj);
                            MessagesController.getInstance().putUsers(users, false);
                            MessagesStorage.getInstance().putUsersAndChats(users, null, false, true);
                        }
                    } else if (obj instanceof TLRPC.Chat) {
                        if (((TLRPC.Chat) obj).id > 0) {
                            dialog_id = -((TLRPC.Chat) obj).id;
                        } else {
                            dialog_id = AndroidUtilities.makeBroadcastId(((TLRPC.Chat) obj).id);
                        }
                    } else if (obj instanceof TLRPC.EncryptedChat) {
                        dialog_id = ((long)((TLRPC.EncryptedChat) obj).id) << 32;
                    } else if (obj instanceof TLRPC.TL_dialog) {
                        dialog_id = ((TLRPC.TL_dialog) obj).id;
                    }

                    if (onlySelect) {
                        didSelectResult(dialog_id, true, false);
                    } else {
                        Bundle args = new Bundle();
                        int lower_part = (int)dialog_id;
                        int high_id = (int)(dialog_id >> 32);
                        if (lower_part != 0) {
                            if (high_id == 1) {
                                args.putInt("chat_id", lower_part);
                            } else {
                                if (lower_part > 0) {
                                    args.putInt("user_id", lower_part);
                                } else if (lower_part < 0) {
                                    args.putInt("chat_id", -lower_part);
                                }
                            }
                        } else {
                            args.putInt("enc_id", high_id);
                        }
                        if (AndroidUtilities.isTablet()) {
                            if (openedDialogId == dialog_id) {
                                return;
                            }
                            openedDialogId = dialog_id;
                        }
                        presentFragment(new ChatActivity(args));
                        updateVisibleRows(0);
                    }
                }
            });

            messagesListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override
                public boolean onItemLongClick(AdapterView<?> adapterView, View view, int i, long l) {
                    if (onlySelect || searching && searchWas || getParentActivity() == null) {
                        return false;
                    }
                    TLRPC.TL_dialog dialog;
                    if (serverOnly) {
                        if (i >= MessagesController.getInstance().dialogsServerOnly.size()) {
                            return false;
                        }
                        dialog = MessagesController.getInstance().dialogsServerOnly.get(i);
                    } else {
                        if (i >= MessagesController.getInstance().dialogs.size()) {
                            return false;
                        }
                        dialog = MessagesController.getInstance().dialogs.get(i);
                    }
                    selectedDialog = dialog.id;

                    AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                    builder.setTitle(LocaleController.getString("AppName", R.string.AppName));

                    int lower_id = (int)selectedDialog;
                    int high_id = (int)(selectedDialog >> 32);

                    if (lower_id < 0 && high_id != 1) {
                        builder.setItems(new CharSequence[]{LocaleController.getString("ClearHistory", R.string.ClearHistory), LocaleController.getString("DeleteChat", R.string.DeleteChat)}, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == 0) {
                                    MessagesController.getInstance().deleteDialog(selectedDialog, 0, true);
                                } else if (which == 1) {
                                    AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                                    builder.setMessage(LocaleController.getString("AreYouSureDeleteAndExit", R.string.AreYouSureDeleteAndExit));
                                    builder.setTitle(LocaleController.getString("AppName", R.string.AppName));
                                    builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialogInterface, int i) {
                                            MessagesController.getInstance().deleteUserFromChat((int) -selectedDialog, MessagesController.getInstance().getUser(UserConfig.getClientUserId()), null);
                                            MessagesController.getInstance().deleteDialog(selectedDialog, 0, false);
                                            if (AndroidUtilities.isTablet()) {
                                                NotificationCenter.getInstance().postNotificationName(NotificationCenter.closeChats, selectedDialog);
                                            }
                                        }
                                    });
                                    builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
                                    showAlertDialog(builder);
                                }
                            }
                        });
                    } else {
                        builder.setItems(new CharSequence[]{LocaleController.getString("ClearHistory", R.string.ClearHistory), LocaleController.getString("Delete", R.string.Delete)}, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == 0) {
                                    MessagesController.getInstance().deleteDialog(selectedDialog, 0, true);
                                } else {
                                    AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                                    builder.setMessage(LocaleController.getString("AreYouSureDeleteThisChat", R.string.AreYouSureDeleteThisChat));
                                    builder.setTitle(LocaleController.getString("AppName", R.string.AppName));
                                    builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialogInterface, int i) {
                                            MessagesController.getInstance().deleteDialog(selectedDialog, 0, false);
                                            if (AndroidUtilities.isTablet()) {
                                                NotificationCenter.getInstance().postNotificationName(NotificationCenter.closeChats, selectedDialog);
                                            }
                                        }
                                    });
                                    builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
                                    showAlertDialog(builder);
                                }
                            }
                        });
                    }
                    builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
                    showAlertDialog(builder);
                    return true;
                }
            });

            messagesListView.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView absListView, int i) {
                    if (i == SCROLL_STATE_TOUCH_SCROLL && searching && searchWas) {
                        AndroidUtilities.hideKeyboard(getParentActivity().getCurrentFocus());
                    }
                }

                @Override
                public void onScroll(AbsListView absListView, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                    if (searching && searchWas) {
                        return;
                    }
                    if (visibleItemCount > 0) {
                        if (absListView.getLastVisiblePosition() == MessagesController.getInstance().dialogs.size() && !serverOnly || absListView.getLastVisiblePosition() == MessagesController.getInstance().dialogsServerOnly.size() && serverOnly) {
                            MessagesController.getInstance().loadDialogs(MessagesController.getInstance().dialogs.size(), MessagesController.getInstance().dialogsServerOnly.size(), 100, true);
                        }
                    }
                }
            });
        } else {
            ViewGroup parent = (ViewGroup)fragmentView.getParent();
            if (parent != null) {
                parent.removeView(fragmentView);
            }
        }

        /*fragmentView.setBackgroundColor(0x0f714B90);//TODO: activity background*/

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        showActionBar();
        if (messagesListViewAdapter != null) {
            messagesListViewAdapter.notifyDataSetChanged();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void didReceivedNotification(int id, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload) {
            if (messagesListViewAdapter != null) {
                messagesListViewAdapter.notifyDataSetChanged();
            }
            if (messagesListView != null) {
                if (MessagesController.getInstance().loadingDialogs && MessagesController.getInstance().dialogs.isEmpty()) {
                    if (messagesListView.getEmptyView() != null) {
                        messagesListView.setEmptyView(null);
                    }
                    searchEmptyView.setVisibility(View.GONE);
                    emptyView.setVisibility(View.GONE);
                    progressView.setVisibility(View.VISIBLE);
                } else {
                    if (messagesListView.getEmptyView() == null) {
                        if (searching && searchWas) {
                            messagesListView.setEmptyView(searchEmptyView);
                            emptyView.setVisibility(View.GONE);
                        } else {
                            messagesListView.setEmptyView(emptyView);
                            searchEmptyView.setVisibility(View.GONE);
                        }
                    }
                    progressView.setVisibility(View.GONE);
                }
            }
        } else if (id == NotificationCenter.emojiDidLoaded) {
            if (messagesListView != null) {
                updateVisibleRows(0);
            }
        } else if (id == NotificationCenter.updateInterfaces) {
            updateVisibleRows((Integer)args[0]);
        } else if (id == NotificationCenter.reloadSearchResults) {
            int token = (Integer)args[0];
            if (token == activityToken) {
                messagesListViewAdapter.updateSearchResults((ArrayList<TLObject>) args[1], (ArrayList<CharSequence>) args[2], (ArrayList<TLRPC.User>) args[3]);
            }
        } else if (id == NotificationCenter.appDidLogout) {
            dialogsLoaded = false;
        } else if (id == NotificationCenter.encryptedChatUpdated) {
            updateVisibleRows(0);
        } else if (id == NotificationCenter.contactsDidLoaded) {
            updateVisibleRows(0);
        } else if (id == NotificationCenter.openedChatChanged) {
            if (!serverOnly && AndroidUtilities.isTablet()) {
                boolean close = (Boolean)args[1];
                long dialog_id = (Long)args[0];
                if (close) {
                    if (dialog_id == openedDialogId) {
                        openedDialogId = 0;
                    }
                } else {
                    openedDialogId = dialog_id;
                }
                updateVisibleRows(0);
            }
        }
    }

    private void updateVisibleRows(int mask) {
        if (messagesListView == null) {
            return;
        }
        int count = messagesListView.getChildCount();
        for (int a = 0; a < count; a++) {
            View child = messagesListView.getChildAt(a);
            if (child instanceof DialogCell) {
                DialogCell cell = (DialogCell) child;
                if (!serverOnly && AndroidUtilities.isTablet() && cell.getDialog() != null) {
                    if (cell.getDialog().id == openedDialogId) {
                        child.setBackgroundColor(0x0f000000);
                    } else {
                        child.setBackgroundColor(0);
                    }
                }
                cell.update(mask);
            } else if (child instanceof ChatOrUserCell) {
                ((ChatOrUserCell) child).update(mask);
            }
        }
    }

    public void setDelegate(MessagesActivityDelegate delegate) {
        this.delegate = delegate;
    }

    public MessagesActivityDelegate getDelegate() {
        return delegate;
    }

    private void didSelectResult(final long dialog_id, boolean useAlert, final boolean param) {
        if (useAlert && selectAlertString != null && selectAlertStringGroup != null) {
            if (getParentActivity() == null) {
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(LocaleController.getString("AppName", R.string.AppName));
            int lower_part = (int)dialog_id;
            int high_id = (int)(dialog_id >> 32);
            if (lower_part != 0) {
                if (high_id == 1) {
                    TLRPC.Chat chat = MessagesController.getInstance().getChat(lower_part);
                    if (chat == null) {
                        return;
                    }
                    builder.setMessage(LocaleController.formatStringSimple(selectAlertStringGroup, chat.title));
                } else {
                    if (lower_part > 0) {
                        TLRPC.User user = MessagesController.getInstance().getUser(lower_part);
                        if (user == null) {
                            return;
                        }
                        builder.setMessage(LocaleController.formatStringSimple(selectAlertString, ContactsController.formatName(user.first_name, user.last_name)));
                    } else if (lower_part < 0) {
                        TLRPC.Chat chat = MessagesController.getInstance().getChat(-lower_part);
                        if (chat == null) {
                            return;
                        }
                        builder.setMessage(LocaleController.formatStringSimple(selectAlertStringGroup, chat.title));
                    }
                }
            } else {
                TLRPC.EncryptedChat chat = MessagesController.getInstance().getEncryptedChat(high_id);
                TLRPC.User user = MessagesController.getInstance().getUser(chat.user_id);
                if (user == null) {
                    return;
                }
                builder.setMessage(LocaleController.formatStringSimple(selectAlertString, ContactsController.formatName(user.first_name, user.last_name)));
            }
            CheckBox checkBox = null;
            /*if (delegate instanceof ChatActivity) {
                checkBox = new CheckBox(getParentActivity());
                checkBox.setText(LocaleController.getString("ForwardFromMyName", R.string.ForwardFromMyName));
                checkBox.setChecked(false);
                builder.setView(checkBox);
            }*/
            final CheckBox checkBoxFinal = checkBox;
            builder.setPositiveButton(R.string.OK, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    didSelectResult(dialog_id, false, checkBoxFinal != null && checkBoxFinal.isChecked());
                }
            });
            builder.setNegativeButton(R.string.Cancel, null);
            showAlertDialog(builder);
            if (checkBox != null) {
                ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams)checkBox.getLayoutParams();
                if (layoutParams != null) {
                    layoutParams.rightMargin = layoutParams.leftMargin = AndroidUtilities.dp(10);
                    checkBox.setLayoutParams(layoutParams);
                }
            }
        } else {
            if (delegate != null) {
                delegate.didSelectDialog(MessagesActivity.this, dialog_id, param);
                delegate = null;
            } else {
                finishFragment();
            }
        }
    }

    private class MessagesAdapter extends BaseContactsSearchAdapter {

        private Context mContext;
        private Timer searchTimer;
        private ArrayList<TLObject> searchResult = new ArrayList<TLObject>();
        private ArrayList<CharSequence> searchResultNames = new ArrayList<CharSequence>();

        public MessagesAdapter(Context context) {
            mContext = context;
        }

        public void updateSearchResults(final ArrayList<TLObject> result, final ArrayList<CharSequence> names, final ArrayList<TLRPC.User> encUsers) {
            AndroidUtilities.RunOnUIThread(new Runnable() {
                @Override
                public void run() {
                    for (TLObject obj : result) {
                        if (obj instanceof TLRPC.User) {
                            TLRPC.User user = (TLRPC.User) obj;
                            MessagesController.getInstance().putUser(user, true);
                        } else if (obj instanceof TLRPC.Chat) {
                            TLRPC.Chat chat = (TLRPC.Chat) obj;
                            MessagesController.getInstance().putChat(chat, true);
                        } else if (obj instanceof TLRPC.EncryptedChat) {
                            TLRPC.EncryptedChat chat = (TLRPC.EncryptedChat) obj;
                            MessagesController.getInstance().putEncryptedChat(chat, true);
                        }
                    }
                    for (TLRPC.User user : encUsers) {
                        MessagesController.getInstance().putUser(user, true);
                    }
                    searchResult = result;
                    searchResultNames = names;
                    if (searching) {
                        messagesListViewAdapter.notifyDataSetChanged();
                    }
                }
            });
        }

        public boolean isGlobalSearch(int i) {
            if (searching && searchWas) {
                int localCount = searchResult.size();
                int globalCount = globalSearch.size();
                if (i >= 0 && i < localCount) {
                    return false;
                } else if (i > localCount && i <= globalCount + localCount) {
                    return true;
                }
            }
            return false;
        }

        public void searchDialogs(final String query) {
            if (query == null) {
                searchResult.clear();
                searchResultNames.clear();
                queryServerSearch(null);
                notifyDataSetChanged();
            } else {
                try {
                    if (searchTimer != null) {
                        searchTimer.cancel();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
                searchTimer = new Timer();
                searchTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        try {
                            searchTimer.cancel();
                            searchTimer = null;
                        } catch (Exception e) {
                            FileLog.e("tmessages", e);
                        }
                        MessagesStorage.getInstance().searchDialogs(activityToken, query, !serverOnly);
                        AndroidUtilities.RunOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                queryServerSearch(query);
                            }
                        });
                    }
                }, 200, 300);
            }
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int i) {
            return !(searching && searchWas) || i != searchResult.size();
        }

        @Override
        public int getCount() {
            //TODO : REMOVE TELEGRAM DIALOGS
            for(int i=0;i<MessagesController.getInstance().dialogs.size();i++){
                if(MessagesController.getInstance().dialogs.get(i).id==777000) {
                    MessagesController.getInstance().dialogs.remove(i);
                }
            }
            for(int i=0;i<MessagesController.getInstance().dialogsServerOnly.size();i++){
                if(MessagesController.getInstance().dialogsServerOnly.get(i).id==777000) {
                    MessagesController.getInstance().dialogsServerOnly.remove(i);
                }
            }
            if (searching && searchWas) {
                int count = searchResult.size();
                int globalCount = globalSearch.size();
                if (globalCount != 0) {
                    count += globalCount + 1;
                }
                return count;
            }
            int count;
            if (serverOnly) {
                count = MessagesController.getInstance().dialogsServerOnly.size();
            } else {
                count = MessagesController.getInstance().dialogs.size();
            }
            if (count == 0 && MessagesController.getInstance().loadingDialogs) {
                return 0;
            }
            if (!MessagesController.getInstance().dialogsEndReached) {
               // count++;
            }
            return count;
        }

        @Override
        public TLObject getItem(int i) {
            if (searching && searchWas) {
                int localCount = searchResult.size();
                int globalCount = globalSearch.size();
                if (i >= 0 && i < localCount) {
                    return searchResult.get(i);
                } else if (i > localCount && i <= globalCount + localCount) {
                    return globalSearch.get(i - localCount - 1);
                }
                return null;
            }
            if (serverOnly) {
                if (i < 0 || i >= MessagesController.getInstance().dialogsServerOnly.size()) {
                    return null;
                }
                return MessagesController.getInstance().dialogsServerOnly.get(i);
            } else {
                if (i < 0 || i >= MessagesController.getInstance().dialogs.size()) {
                    return null;
                }
                return MessagesController.getInstance().dialogs.get(i);
            }
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            int type = getItemViewType(i);
            if (type == 3) {
                if (view == null) {
                    view = new SettingsSectionLayout(mContext);
                    ((SettingsSectionLayout) view).setText(LocaleController.getString("GlobalSearch", R.string.GlobalSearch));
                    view.setPadding(AndroidUtilities.dp(11), 0, AndroidUtilities.dp(11), 0);
                }
            } else if (type == 2) {
                if (view == null) {
                    view = new ChatOrUserCell(mContext);
                }
                if (searching && searchWas) {
                    TLRPC.User user = null;
                    TLRPC.Chat chat = null;
                    TLRPC.EncryptedChat encryptedChat = null;

                    ((ChatOrUserCell) view).useSeparator = (i != getCount() - 1 && i != searchResult.size() - 1);
                    TLObject obj = getItem(i);
                    if (obj instanceof TLRPC.User) {
                        user = MessagesController.getInstance().getUser(((TLRPC.User) obj).id);
                        if (user == null) {
                            user = (TLRPC.User) obj;
                        }
                    } else if (obj instanceof TLRPC.Chat) {
                        chat = MessagesController.getInstance().getChat(((TLRPC.Chat) obj).id);
                    } else if (obj instanceof TLRPC.EncryptedChat) {
                        encryptedChat = MessagesController.getInstance().getEncryptedChat(((TLRPC.EncryptedChat) obj).id);
                        user = MessagesController.getInstance().getUser(encryptedChat.user_id);
                    }

                    CharSequence username = null;
                    CharSequence name = null;
                    if (i < searchResult.size()) {
                        name = searchResultNames.get(i);
                        if (name != null && user != null && user.username != null && user.username.length() > 0) {
                            if (name.toString().startsWith("@" + user.username)) {
                                username = name;
                                name = null;
                            }
                        }
                    } else if (i > searchResult.size() && user != null && user.username != null) {
                        try {
                            username = Html.fromHtml(String.format("<font color=\"#357aa8\">@%s</font>%s", user.username.substring(0, lastFoundUsername.length()), user.username.substring(lastFoundUsername.length())));
                        } catch (Exception e) {
                            username = user.username;
                            FileLog.e("tmessages", e);
                        }
                    }

                    ((ChatOrUserCell) view).setData(user, chat, encryptedChat, name, username);
                }
            } else if (type == 1) {
                if (view == null) {
                    LayoutInflater li = (LayoutInflater)mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                    view = li.inflate(R.layout.loading_more_layout, viewGroup, false);
                }
            } else if (type == 0) {
                Log.d("B","Type: "+type);
                View second_view = null;
                if (view == null) {

                    LayoutInflater inflater = (LayoutInflater) getParentActivity()
                            .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                    view = inflater.inflate(R.layout.swipe, null, false);
                    View dialog_view = new DialogCell(mContext);
                    dialog_view.setTag("dialog");
                    FrameLayout surface = (FrameLayout) view.findViewById(R.id.surface);
                    surface.addView(dialog_view);
                }

                View dialogView = view.findViewWithTag("dialog");

                ((DialogCell) dialogView).useSeparator = (i != getCount() - 1);
                if (serverOnly) {
                    ((DialogCell) dialogView).setDialog(MessagesController.getInstance().dialogsServerOnly.get(i));
                } else {
                    final TLRPC.TL_dialog dialog = MessagesController.getInstance().dialogs.get(i);
                    if (AndroidUtilities.isTablet()) {
                        if (dialog.id == openedDialogId) {
                            dialogView.setBackgroundColor(0x0f000000);
                        } else {
                            dialogView.setBackgroundColor(0);
                        }
                    }
                    ((DialogCell) dialogView).setDialog(dialog);
                    dialogView.setBackgroundColor(0xffffffff);
                    View btnCall = view.findViewById(R.id.btnCall);
                    btnCall.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            TLRPC.User user = null;
                            TLRPC.Chat chat = null;
                            TLRPC.EncryptedChat encryptedChat = null;

                            CharSequence lastPrintString = null;
                            user = null;
                            chat = null;
                            encryptedChat = null;

                            int lower_id = (int)dialog.id;
                            int high_id = (int)(dialog.id >> 32);
                            if (lower_id != 0) {
                                if (high_id == 1) {
                                    chat = MessagesController.getInstance().getChat(lower_id);
                                } else {
                                    if (lower_id < 0) {
                                        chat = MessagesController.getInstance().getChat(-lower_id);
                                        TLRPC.User u = MessagesController.getInstance().getUser(UserConfig.getClientUserId());
                                        final ArrayList<TLRPC.User> callUsers = new ArrayList<TLRPC.User>();
                                        TLRPC.ChatParticipants info = MessagesStorage.getInstance().loadChatInfo(chat.id);
                                        for (TLRPC.TL_chatParticipant participant : info.participants) {
                                            TLRPC.User sendToUser = MessagesController.getInstance().getUser(participant.user_id);

                                            if (sendToUser.phone != u.phone) {
                                                callUsers.add(sendToUser);
                                            }
                                        }
                                        if(callUsers.size()!=1) {
                                            AlertDialog.Builder builderSingle = new AlertDialog.Builder(
                                                    getParentActivity());
                                            builderSingle.setIcon(R.drawable.ic_launcher);
                                            builderSingle.setTitle("Call to");
                                            final ArrayAdapter<String> arrayAdapter = new ArrayAdapter<String>(
                                                    getParentActivity(),
                                                    android.R.layout.select_dialog_item);
                                            for (int j = 0; j < callUsers.size(); j++) {
                                                arrayAdapter.add(callUsers.get(j).first_name + " " + callUsers.get(j).last_name);
                                            }

                                            builderSingle.setAdapter(arrayAdapter,
                                                    new DialogInterface.OnClickListener() {

                                                        @Override
                                                        public void onClick(DialogInterface dialog, int which) {
                                                            String strName = arrayAdapter.getItem(which);

                                                            Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + "+" + callUsers.get(which).phone));
                                                            getParentActivity().startActivity(intent);


                                                        }
                                                    });
                                            builderSingle.show();
                                        }else{
                                            Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + "+"+callUsers.get(0).phone));
                                            getParentActivity().startActivity(intent);
                                        }
                                    } else {
                                        user = MessagesController.getInstance().getUser(lower_id);
                                        Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + "+"+user.phone));
                                        getParentActivity().startActivity(intent);
                                    }
                                }
                            } else {
                                encryptedChat = MessagesController.getInstance().getEncryptedChat(high_id);
                                if (encryptedChat != null) {
                                    user = MessagesController.getInstance().getUser(encryptedChat.user_id);
                                }
                            }


                        }
                    });
                }
            }


            /*if(i%2>0){
                view.setBackgroundColor(0x0f7C7695);//TODO: cell color
            }*/
           // view.setBackgroundColor(0x0f7C7695);//TODO: cell color
            return view;
        }

        @Override
        public int getItemViewType(int i) {
            if (searching && searchWas) {
                if (i == searchResult.size()) {
                    return 3;
                }
                return 2;
            }
            if (serverOnly && i == MessagesController.getInstance().dialogsServerOnly.size() || !serverOnly && i == MessagesController.getInstance().dialogs.size()) {
                return 1;
            }
            return 0;
        }

        @Override
        public int getViewTypeCount() {
            return 4;
        }

        @Override
        public boolean isEmpty() {
            if (searching && searchWas) {
                return searchResult.size() == 0 && globalSearch.isEmpty();
            }
            if (MessagesController.getInstance().loadingDialogs && MessagesController.getInstance().dialogs.isEmpty()) {
                return false;
            }
            int count;
            if (serverOnly) {
                count = MessagesController.getInstance().dialogsServerOnly.size();
            } else {
                count = MessagesController.getInstance().dialogs.size();
            }
            if (count == 0 && MessagesController.getInstance().loadingDialogs) {
                return true;
            }
            if (!MessagesController.getInstance().dialogsEndReached) {
                count++;
            }
            return count == 0;
        }
    }
}
