package com.owncloud.android.ui.tasks;

import android.accounts.Account;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;

import com.owncloud.android.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by jp on 1/2/16.
 */
public class SelectAccountsFromListTask
{
  public interface Listener {
    void onSelectedAccounts(List<Account> selectedAccounts);
  }

  static public void start(final Activity      activity,
                           final String        title,
                           final List<Account> accounts,
                           final List<String>  preSelectedAccountNames,
                           final Listener      listener) {

    List<CharSequence> namesOfAccountsInList = toListOfCharSequence(accounts);

    final boolean[] itemsCheckingState = createItemsCheckedStateArray(accounts, preSelectedAccountNames);

    DialogInterface.OnMultiChoiceClickListener onMultiChoiceClickListener =
            new DialogInterface.OnMultiChoiceClickListener() {
              public void onClick(DialogInterface dialog, int which, boolean aIsChecked) {
                itemsCheckingState[which] = aIsChecked;
              }
            };

    DialogInterface.OnClickListener onClickListener =
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int which) {
                if (which == AlertDialog.BUTTON_POSITIVE) {
                  List<Account> selectedAccounts = new ArrayList<>();
                  for (int index = 0; index < accounts.size(); index++) {
                    if (itemsCheckingState[index] == true) {
                      selectedAccounts.add(accounts.get(index));
                    }
                  }
                  listener.onSelectedAccounts(selectedAccounts);
                }
              }
            };

    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    builder.setTitle(title);
    builder.setMultiChoiceItems(namesOfAccountsInList.toArray(new CharSequence[namesOfAccountsInList.size()]),
                                itemsCheckingState, //createItemsCheckedStateArray(accounts, preSelectedAccountNames),
                                onMultiChoiceClickListener);
    builder.setPositiveButton(activity.getString(R.string.common_ok), onClickListener);
    builder.setNegativeButton(activity.getString(R.string.common_cancel), null);
    builder.create().show();
  }

  static private List<CharSequence> toListOfCharSequence(List<Account> accounts) {
    List<CharSequence> result = new ArrayList<>();
    for (Account account: accounts) {
      result.add(account.name);
    }
    return result;
  }

  static private boolean[] createItemsCheckedStateArray(List<Account> accounts, List<String> preSelectedAccountNames) {
    boolean[] result = new boolean[accounts.size()];
    for (int index = 0; index < accounts.size(); index++) {
      final String accountName = accounts.get(index).name;
      result[index] = preSelectedAccountNames.contains(accountName);
    }
    return result;
  }
}
