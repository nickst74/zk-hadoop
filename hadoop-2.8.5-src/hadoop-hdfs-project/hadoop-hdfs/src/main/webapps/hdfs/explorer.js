/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
(function() {
  "use strict";

  // The chunk size of tailing the files, i.e., how many bytes will be shown
  // in the preview.
  var TAIL_CHUNK_SIZE = 32768;

  //This stores the current directory which is being browsed
  var current_directory = "";

  function show_err_msg(msg) {
    $('#alert-panel-body').html(msg);
    $('#alert-panel').show();
  }

  $(window).bind('hashchange', function () {
    $('#alert-panel').hide();

    var dir = window.location.hash.slice(1);
    if(dir == "") {
      dir = "/";
    }
    if(current_directory != dir) {
      browse_directory(dir);
    }
  });

  function network_error_handler(url) {
    return function (jqxhr, text, err) {
      switch(jqxhr.status) {
        case 401:
          var msg = '<p>Authentication failed when trying to open ' + url + ': Unauthorized.</p>';
          break;
        case 403:
          if(jqxhr.responseJSON !== undefined && jqxhr.responseJSON.RemoteException !== undefined) {
            var msg = '<p>' + jqxhr.responseJSON.RemoteException.message + "</p>";
            break;
          }
          var msg = '<p>Permission denied when trying to open ' + url + ': ' + err + '</p>';
          break;
        case 404:
          var msg = '<p>Path does not exist on HDFS or WebHDFS is disabled.  Please check your path or enable WebHDFS</p>';
          break;
        default:
          var msg = '<p>Failed to retrieve data from ' + url + ': ' + err + '</p>';
        }
      show_err_msg(msg);
    };
  }

  function append_path(prefix, s) {
    var l = prefix.length;
    var p = l > 0 && prefix[l - 1] == '/' ? prefix.substring(0, l - 1) : prefix;
    return p + '/' + s;
  }

  function get_response(data, type) {
    return data[type] !== undefined ? data[type] : null;
  }

  function get_response_err_msg(data) {
    return data.RemoteException !== undefined ? data.RemoteException.message : "";
  }

  function delete_path(inode_name, absolute_file_path) {
    $('#delete-modal-title').text("Delete - " + inode_name);
    $('#delete-prompt').text("Are you sure you want to delete " + inode_name
      + " ?");

    $('#delete-button').click(function() {
      // DELETE /webhdfs/v1/<path>?op=DELETE&recursive=<true|false>
      var url = '/webhdfs/v1' + encode_path(absolute_file_path) +
        '?op=DELETE' + '&recursive=true';

      $.ajax(url,
        { type: 'DELETE'
        }).done(function(data) {
          browse_directory(current_directory);
        }).error(network_error_handler(url)
         ).complete(function() {
           $('#delete-modal').modal('hide');
           $('#delete-button').button('reset');
        });
    })
    $('#delete-modal').modal();
  }

  /* This method loads the checkboxes on the permission info modal. It accepts
   * the octal permissions, eg. '644' or '755' and infers the checkboxes that
   * should be true and false
   */
  function view_perm_details(e, filename, abs_path, perms) {
    $('.explorer-perm-links').popover('destroy');
    e.popover({html: true, content: $('#explorer-popover-perm-info').html(), trigger: 'focus'})
      .on('shown.bs.popover', function(e) {
        var popover = $(this), parent = popover.parent();
        //Convert octal to binary permissions
        var bin_perms = parseInt(perms, 8).toString(2);
        bin_perms = bin_perms.length == 9 ? "0" + bin_perms : bin_perms;
        parent.find('#explorer-perm-cancel').on('click', function() { popover.popover('destroy'); });
        parent.find('#explorer-set-perm-button').off().click(function() { set_permissions(abs_path); });
        parent.find('input[type=checkbox]').each(function(idx, element) {
          var e = $(element);
          e.prop('checked', bin_perms.charAt(9 - e.attr('data-bit')) == '1');
        });
      })
      .popover('show');
  }

  // Use WebHDFS to set permissions on an absolute path
  function set_permissions(abs_path) {
    var p = 0;
    $.each($('.popover .explorer-popover-perm-body input:checked'), function(idx, e) {
      p |= 1 << (+$(e).attr('data-bit'));
    });

    var permission_mask = p.toString(8);

    // PUT /webhdfs/v1/<path>?op=SETPERMISSION&permission=<permission>
    var url = '/webhdfs/v1' + encode_path(abs_path) +
      '?op=SETPERMISSION' + '&permission=' + permission_mask;

    $.ajax(url, { type: 'PUT'
      }).done(function(data) {
        browse_directory(current_directory);
      }).error(network_error_handler(url))
      .complete(function() {
        $('.explorer-perm-links').popover('destroy');
      });
  }

  function encode_path(abs_path) {
    abs_path = encodeURIComponent(abs_path);
    var re = /%2F/g;
    return abs_path.replace(re, '/');
  }

  function view_file_details(path, abs_path) {
    function show_block_info(blocks) {
      var menus = $('#file-info-blockinfo-list');
      menus.empty();

      menus.data("blocks", blocks);
      menus.change(function() {
        var d = $(this).data('blocks')[$(this).val()];
        if (d === undefined) {
          return;
        }
        //console.log(d);
        // if in corruptor mode prepare layout
        if(document.getElementById("corruptor-mode-checkbox").checked) {
          // first clear previous options
          $("#corruptor-dnode").empty();
          // get all datanodes and create the dropdown
          const dnode_selector = document.getElementById("corruptor-dnode");
          for(var i = 0; i < d.locations.length; i++) {
            // create dnode option for every block location
            const dnode_option = document.createElement("option");
            dnode_option.text = d.locations[i].hostName;
            // corruptor servlet runs on info port, so set value accordingly
            dnode_option.value = d.locations[i].ipAddr + ":" + d.locations[i].infoPort;
            // add option to selector
            dnode_selector.appendChild(dnode_option);
            // also set hidden values for later
            document.getElementById("blockpoolId").value = d.block.blockPoolId;
            document.getElementById("blockId").value = d.block.blockId;
          }
          // initialize with first option
          dnode_selector.selectedIndex = 0;
          $("#corruptor-div").show();
        } else {
          // else hide layout
          $("#corruptor-div").hide();
        }

        dust.render('block-info', d, function(err, out) {
          $('#file-info-blockinfo-body').html(out);
        });

      });
      for (var i = 0; i < blocks.length; ++i) {
        var item = $('<option value="' + i + '">Block ' + i + '</option>');
        menus.append(item);
      }
      menus.change();
    }

    abs_path = encode_path(abs_path);
    var url = '/webhdfs/v1' + abs_path + '?op=GET_BLOCK_LOCATIONS';
    $.ajax({url: url, dataType: 'text'}).done(function(data_text) {
      var data = JSONParseBigNum(data_text);
      var d = get_response(data, "LocatedBlocks");
      if (d === null) {
        show_err_msg(get_response_err_msg(data));
        return;
      }

      $('#file-info-tail').hide();
      $('#file-info-title').text("File information - " + path);

      var download_url = '/webhdfs/v1' + abs_path + '?op=OPEN';

      $('#file-info-download').attr('href', download_url);
      $('#file-info-preview').click(function() {
        var offset = d.fileLength - TAIL_CHUNK_SIZE;
        var url = offset > 0 ? download_url + '&offset=' + offset : download_url;
        $.get(url, function(t) {
          $('#file-info-preview-body').val(t);
          $('#file-info-tail').show();
        }, "text").error(network_error_handler(url));
      });

      if (d.fileLength > 0) {
        /**** ADD STATUS TO ALL BLOCKS OF FILE ****/
        for(var i = 0; i < d.locatedBlocks.length; i++) {
          const blockId = d.locatedBlocks[i].block.blockId;
          var corrupt = false, healthy = false;
          if(block_status[blockId] !== undefined) {
            d.locatedBlocks[i].block_status = block_status[blockId];
            for(var j = 0; j < d.locatedBlocks[i].locations.length; j++) {
              const bc_addr = d.locatedBlocks[i].locations[j].blockchainAddress;
              const dnode_status = block_status[blockId].per_dnode[bc_addr];
              d.locatedBlocks[i].locations[j].status = dnode_status;
              if(dnode_status !== undefined) {
                corrupt ||= dnode_status.wrong;
                healthy ||= !dnode_status.wrong;
              }
            }
            // just a health status mark for the block
            if(!corrupt && healthy)
              d.locatedBlocks[i].block_status.health = "x2705"; // All good
            else if(corrupt && !healthy)
              d.locatedBlocks[i].block_status.health = "x26D4"; // All corrupt
            else if(corrupt && healthy)
              d.locatedBlocks[i].block_status.health = "x2757"; // Some corrupt
            else
              d.locatedBlocks[i].block_status.health = "x2754"; // Fate unknown
          }
        }
        show_block_info(d.locatedBlocks);
        $('#file-info-blockinfo-panel').show();
      } else {
        $('#file-info-blockinfo-panel').hide();
      }
      $('#file-info').modal();
    }).error(network_error_handler(url));
  }

  /**Use X-editable to make fields editable with a nice UI.
   * elementType is the class of element(s) you want to make editable
   * op is the WebHDFS operation that will be triggered
   * parameter is (currently the 1) parameter which will be passed along with
   *   the value entered by the user
   */
  function makeEditable(elementType, op, parameter) {
    $(elementType).each(function(index, value) {
      $(this).editable({
        url: function(params) {
          var inode_name = $(this).closest('tr').attr('inode-path');
          var absolute_file_path = append_path(current_directory, inode_name);
          var url = '/webhdfs/v1' + encode_path(absolute_file_path) + '?op=' +
            op + '&' + parameter + '=' + encodeURIComponent(params.value);

          return $.ajax(url, { type: 'PUT', })
            .error(network_error_handler(url))
            .success(function() {
                browse_directory(current_directory);
             });
        },
        error: function(response, newValue) {return "";}
      });
    });
  }

  function func_size_render(data, type, row, meta) {
    if(type == 'display') {
      return dust.filters.fmt_bytes(data);
    }
    else return data;
  }

  // Change the format of date-time depending on how old the
  // the timestamp is. If older than 6 months, no need to be
  // show exact time.
  function func_time_render(data, type, row, meta) {
    if(type == 'display') {
      var cutoff = moment().subtract(6, 'months').unix() * 1000;
      if(data < cutoff) {
        return moment(Number(data)).format('MMM DD YYYY');
      } else {
        return moment(Number(data)).format('MMM DD HH:mm');
      }
    }
    return data;
  }

  function browse_directory(dir) {
    var HELPERS = {
      'helper_date_tostring' : function (chunk, ctx, bodies, params) {
        var value = dust.helpers.tap(params.value, chunk, ctx);
        return chunk.write('' + moment(Number(value)).format('ddd MMM DD HH:mm:ss ZZ YYYY'));
      }
    };
    var url = '/webhdfs/v1' + encode_path(dir) + '?op=LISTSTATUS';
    $.get(url, function(data) {
      var d = get_response(data, "FileStatuses");
      if (d === null) {
        show_err_msg(get_response_err_msg(data));
        return;
      }

      current_directory = dir;
      $('#directory').val(dir);
      window.location.hash = dir;
      var base = dust.makeBase(HELPERS);

      /**** ADD STATUS TO FILE ****/
      //console.log(d.FileStatus);
      for (var i = 0; i < d.FileStatus.length; i++) {
        //console.log(d.FileStatus[i]);
        if (d.FileStatus[i].type === "FILE") {
          /*
           * status code:
           * 0 -> OK
           * 1 -> not OK
           * 2 -> Unknown
           * 3 -> all replicas of a block are corrupt
           * 4 -> Well if this happens... the file must have come from outer space.
           */
          var code = 0;
          var filepath = current_directory;
          if(d.FileStatus[i].pathSuffix !== "") {
            // if we searched a directory then search through dir/filename
            filepath += '/' + d.FileStatus[i].pathSuffix;
          } else {
            // else if searched specific file view details too
            view_file_details(filepath.split('/').at(-1), filepath);
          }
          // just remove multiple '/' so it can match with fsck output
          filepath = filepath.replaceAll(/\/+/ig, '/');
          
          const block_list = file_to_blocklist[filepath];
          if(block_list !== undefined) {
            block_list.some(blockId => {
              if(block_status[blockId] !== undefined) {
                var found_healthy = false, found_corrupt = false;
                for(const [_, status] of Object.entries(block_status[blockId].per_dnode)) {
                  found_healthy ||= !status.wrong;
                  found_corrupt ||= status.wrong;
                }
                if(!found_healthy) {
                  // no healthy replica
                  code = 3;
                  return true;
                } else if(found_corrupt) {
                  // both healthy and corrupt
                  code = Math.max(code, 1);
                }
              } else {
                // no reports for block. Fate unknown
                code = 2;
              }
            });
          } else {
            code = 4;
            console.warn("File not visible from fsck. Seems fishy, try reloading.");
          }
          // finally insert the right symbol
          switch (code) {
            case 0:
              d.FileStatus[i].blockHealth = "x2705";
              break;
            case 1:
              d.FileStatus[i].blockHealth = "x2757";
              break;
            case 2:
              d.FileStatus[i].blockHealth = "x2754";
              break;
            case 3:
              d.FileStatus[i].blockHealth = "x26D4";
              break;
            case 4:
              d.FileStatus[i].blockHealth = "x1F47D";
              break;
            default:
              d.FileStatus[i].blockHealth = "x2754";
              break;
          }
        }
        
      }
      dust.render('explorer', base.push(d), function(err, out) {
        $('#panel').html(out);


        $('.explorer-browse-links').click(function() {
          var type = $(this).attr('inode-type');
          var path = $(this).closest('tr').attr('inode-path');
          var abs_path = append_path(current_directory, path);
          if (type == 'DIRECTORY') {
            browse_directory(abs_path);
          } else {
            //console.log(path, abs_path);
            view_file_details(path, abs_path);
          }
        });

        //Set the handler for changing permissions
        $('.explorer-perm-links').click(function() {
          var filename = $(this).closest('tr').attr('inode-path');
          var abs_path = append_path(current_directory, filename);
          var perms = $(this).closest('tr').attr('data-permission');
          view_perm_details($(this), filename, abs_path, perms);
        });

        makeEditable('.explorer-owner-links', 'SETOWNER', 'owner');
        makeEditable('.explorer-group-links', 'SETOWNER', 'group');
        makeEditable('.explorer-replication-links', 'SETREPLICATION', 'replication');

        $('.explorer-entry .glyphicon-trash').click(function() {
          var inode_name = $(this).closest('tr').attr('inode-path');
          var absolute_file_path = append_path(current_directory, inode_name);
          delete_path(inode_name, absolute_file_path);
        });
          
          $('#table-explorer').dataTable( {
              'lengthMenu': [ [25, 50, 100, -1], [25, 50, 100, "All"] ],
              'columns': [
                  {'searchable': false }, //Permissions
                  null, //Owner
                  null, //Group
                  { 'searchable': false, 'render': func_size_render}, //Size
                  { 'searchable': false, 'render': func_time_render}, //Last Modified
                  { 'searchable': false }, //Replication
                  null, //Block Size
                  { 'searchable': false }, //Block Health
                  null, //Name
                  { 'sortable' : false } //Trash
              ],
              "deferRender": true
          });
        toggle_table_theme(document.getElementById("corruptor-mode-checkbox").checked);
          
      });
    }).error(network_error_handler(url));
  }


  function init() {
    dust.loadSource(dust.compile($('#tmpl-explorer').html(), 'explorer'));
    dust.loadSource(dust.compile($('#tmpl-block-info').html(), 'block-info'));

    // just a helper function to format datetime
    dust.helpers.format_time = function (chunk, ctx, bodies, params) {
      var value = dust.helpers.tap(params.value, chunk, ctx);
      return chunk.write(format_time(value));
    };

    var b = function() { browse_directory($('#directory').val()); };
    $('#btn-nav-directory').click(b);
    //Also navigate to the directory when a user presses enter.
    $('#directory').on('keyup', function (e) {
      if (e.which == 13) {
        browse_directory($('#directory').val());
      }
    });
    var dir = window.location.hash.slice(1);
    if(dir == "") {
      window.location.hash = "/";
    } else {
      browse_directory(dir);
    }
  }

  $('#btn-create-directory').on('show.bs.modal', function(event) {
    $('#new_directory_pwd').text(current_directory);
  });

  $('#btn-create-directory-send').click(function () {
    $(this).prop('disabled', true);
    $(this).button('complete');

    var url = '/webhdfs/v1' + encode_path(append_path(current_directory,
      $('#new_directory').val())) + '?op=MKDIRS';

    $.ajax(url, { type: 'PUT' }
    ).done(function(data) {
      browse_directory(current_directory);
    }).error(network_error_handler(url)
     ).complete(function() {
       $('#btn-create-directory').modal('hide');
       $('#btn-create-directory-send').button('reset');
    });
  })

  /**
   * Parsing function for BlockReport events from blockchain.
   * Specific for explorer.html use, returns status only for existing blocks
   * on active datanodes (we are browsing the current state of the hdfs)
   * @param {list} events List of BlockReport events 
   * @returns Dict with number of reports per block/datanode
   */
  function parse_events(events) {
    const block_status_dict = {};

    events.forEach(event => {
      const dnode_addr = event.returnValues.datanode.toLowerCase();
      // keep reports from active datanodes only
      if(bc_to_ip[dnode_addr] !== undefined) {
        event.returnValues.blocks.forEach(blockId => {
          // parse all blockIds reported but keep only the active ones
          if(block_to_file[blockId] !== undefined) {
            if(block_status_dict[blockId] === undefined) {
              block_status_dict[blockId] = {
                per_dnode: {},
                total: 0,
                wrong: 0,
                last_update: 0
              };
            }
            if (block_status_dict[blockId].per_dnode[dnode_addr] === undefined) {
              block_status_dict[blockId].per_dnode[dnode_addr] = {
                total: 0,
                wrong: 0
              };
            }
            block_status_dict[blockId].total++;
            block_status_dict[blockId].per_dnode[dnode_addr].total++;
            block_status_dict[blockId].last_update = Math.max(block_status_dict[blockId].last_update, event.returnValues.time);
          }
        });
        //parse wrong reports
        event.returnValues.corrupted.forEach(blockId => {
          if(block_to_file[blockId] !== undefined) {
            // now count the wrong reports
            block_status_dict[blockId].wrong++;
            block_status_dict[blockId].per_dnode[dnode_addr].wrong++;
          }
        })
      }
    });
    return block_status_dict;
  }

  // get background theme first
  toggle_background_theme(document.getElementById("corruptor-mode-checkbox").checked);

  // toggle theme main function
  function toggle_theme(flag) {
    toggle_background_theme(flag);
    toggle_table_theme(flag);
  }

  // just a listener for the corruptor mode checkbox to change the theme
  document.getElementById("corruptor-mode-checkbox").addEventListener('change', e => {toggle_theme(e.target.checked)});

  document.getElementById("info-btn").addEventListener('click', _ => {
    // just show a popup with usage info
    const html_body = "<div style=\"text-align:left\">"+
                        "<strong><u>Corruptor mode:</u></strong> enables an extra form in the file details window that lets you pick a block from the file of your choice "+
                        "and corrupt it on any Datanode that contains a replica. Can be enabled from the upper-right switch.<hr>"+
                        "<strong><u>Icons info</u></strong>"+
                        "<ul><li>&#x2705 : No corrupt block replicas found</li>"+
                        "<li>&#x2757 : Some replicas of a block are corrupt</li>"+
                        "<li>&#x26D4 : <strong>All</strong> replicas of a block are corrupt</li>"+
                        "<li>&#x2754 : No reports found for the block/file</li>"+
                        "<li>&#x1F47D : You might have to reload the page</li></ul></div><hr>"
    Swal.fire({
      type: "info",
      icon: "question",
      confirmButtonText: "Got it!",
      confirmButtonColor: "#5fa33e",
      html: html_body
    })
  })
  /*********************************************************/

  var block_status;

  blockchain(parse_events).then(data => {
    block_status = data;
    init();
  });
})();

function corrupt_button_pressed() {
  // get all corruptor-form values
  const blockpoolId = $("#blockpoolId").val();
  const blockId = $("#blockId").val();
  const dnode_addr = $("#corruptor-dnode :selected").val();
  const mode = $("#corruptor-mode :selected").val();
  const perc = $("#corruptor-range").val();
  const hostname = $("#corruptor-dnode :selected").text();
  // prepare request to corruptor servlet
  const url = new URL("http://"+dnode_addr+"/corrupt");
  url.searchParams.set('blockpool', blockpoolId);
  url.searchParams.set('blockId', blockId);
  url.searchParams.set('perc', perc);
  url.searchParams.set('clustered', mode);
  // create popup text
  const html_body = "<div style=\"text-align: left\"><ul><li>BlockPoolId: "+blockpoolId+"</li>"
                    +"<li>BlockId: "+blockId+"</li>"
                    +"<li>Datanode: "+hostname+" ("+dnode_addr+")</li>"
                    +"<li>Corruption Mode: "+(mode === "true" ? "Clustered" : "Random")+"</li>"
                    +"<li>Percentage: "+perc+"%</li></ul></div>"
  //console.log(url);
  // open popup for confirmation (created with sweetalert2)
  Swal.fire({
    title: "Are you sure you want to corrupt this block?",
    icon: "warning",
    html: html_body,
    footer: "<span style=\"color:red\">Warning: This process is irreversible!</span>",
    showCancelButton: true,
    confirmButtonText: 'Corrupt It!',
    confirmButtonColor: "#ff3131",
    showLoaderOnConfirm: true,
    preConfirm: _ => {
      return fetch(url, {
        method: 'POST',
        mode: 'cors'
      })
      .then(response => {
        if(!response.ok) {
          throw new Error(response.statusText);
        }
      })
      .catch(error => {
        Swal.showValidationMessage("Request failed<br>"+error);
      })
    },
    allowOutsideClick: () => !Swal.isLoading()
  }).then(result => {
    if(result.isConfirmed) {
      Swal.fire({
        title: "Block corrupt successfully",
        icon: "success",
        showConfirmButton: false,
        timer: 1500
      })
    }
  });

}

/* utility functions to toggle corruptor mode*/
function toggle_background_theme(flag) {
  if(flag) {
    document.body.classList.add("dark-mode");
  } else {
    document.body.classList.remove("dark-mode");
  }
}
function toggle_table_theme(flag){
  if(flag) {
    document.getElementById("table-explorer").classList.add("table-dark-mode");
  } else {
    document.getElementById("table-explorer").classList.remove("table-dark-mode");
  }
}