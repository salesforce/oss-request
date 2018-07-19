$(function () {

  $('#commentsModal').on('show.bs.modal', function (event) {
    const button = $(event.relatedTarget);
    const modal = $(this);
    modal.find('.modal-body').empty();
    modal.find('.modal-footer').children().hide();

    const requestSlug = button.data('requestslug');
    const taskId = button.data('taskid');

    if (taskId != null) {
      modal.find('.modal-title').text('Comments');

      const commentsUrl = '/request/' + requestSlug + '/task/' + taskId + '/comments';

      const newCommentForm = $('#newComment');

      newCommentForm.attr('action', commentsUrl);
      newCommentForm.show();

      $.get(commentsUrl, function (data) {
        modal.find('.modal-body').html(anchorme(data));
      });
    }
  });

  $('#confirmModal').on('show.bs.modal', function (event) {
    const button = $(event.relatedTarget);
    const modal = $(this);

    const action = button.data('action');
    const url = button.data('url');

    $('#confirmButton').click(function () {
      $.ajax({
        url: url,
        type: action,
        success: function() {
          location.reload();
        },
        error: function (error) {
          const body = $('.modal-body').html(error.responseText);
          modal.find('.modal-header').after(body);
        }
      });
    });
  });

  $('#reassignModal').on('show.bs.modal', function (event) {
    const button = $(event.relatedTarget);
    const modal = $(this);

    const url = button.data('url');
    const groups = button.data('groups').split(',');

    const reassignGroup = modal.find('#reassignGroup');

    const groupOptions = groups.map(function(group) {
      return $('<option>').text(group)
    });

    const defaultOption = $('<option>').text('Select Group').attr('disabled','disabled').attr('selected', 'selected');

    reassignGroup.html(groupOptions).prepend(defaultOption);

    // todo: disable button until either email is entered or group selected

    $('#reassignButton').click(function () {
      // todo: validate email is a valid looking email
      const reassignEmail = $('#reassignEmail').val();
      const reassignGroup = $('#reassignGroup').val();

      function data() {
        if ((reassignEmail != null) && (reassignEmail.length > 0)) {
          return {email: reassignEmail};
        }
        else if (reassignGroup != null) {
          return {group: reassignGroup};
        }
        else {
          return {};
        }
      }

      $.ajax({
        url: url,
        type: 'post',
        data: JSON.stringify(data()),
        contentType: 'application/json; charset=utf-8',
        success: function() {
          location.reload();
        },
        error: function (error) {
          $('#reassignError').html(error.responseText);
        }
      });
    });
  });

});
