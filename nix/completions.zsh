#compdef clickup-cli

function _clickup-cli(){
  local cmds=(
    'configure:Configure ClickUp CLI'
    'task:Task operations'
    'timelog:Timelog operations'
  )

  local task_cmds=(
    'summary:Show a summary of a ClickUp task'
  )

  local timelog_cmds=(
    'list:List ClickUp time entries'
    'add:Add time entry to ClickUp'
    'compare:Compare local time entries with the ClickUp ones'
    'summary:Show ClickUp time summary'
  )

  _arguments -C "1: :{_describe 'command' cmds}" '*::arg:->args'

  local help=(
    '--help[Display the help text]'
  )

  case $words[1] in
    configure)
      _arguments -C $help
      ;;

    task)
      _arguments -C "1: :{_describe 'command' task_cmds}" '*::arg:->args'

      case $words[1] in
        summary)
          local task_summary_help=(
            '--help[Display the help text]'
            '--task-id[The ID of a task]'
            '--detailed[Whether to show the detailed information or not]'
          )
          _arguments -C $task_summary_help
          ;;

        *)
          _arguments -C $help
          ;;
      esac

      ;;

    timelog)
      _arguments -C "1: :{_describe 'command' timelog_cmds}" '*::arg:->args'

      case $words[1] in
        list)
          local timelog_list_help=(
            '--range[The range shortcut: this-week, last-week, this-month, last-month]:range:(this-week last-week this-month last-month)'
            '--start[The start date. Example: 2022-10-01]'
            '--end[The end date. Example: 2022-10-30]'
            '--help[Display the help text]'
          )
          _arguments -C $timelog_list_help
          ;;

        add)
          local timelog_add_help=(
            '--help[Display the help text]'
            '--task-id[The ID of a task]'
            '--date[An ISO date time of the entry. Example: 2022-01-03T17:09:35.705186Z]'
            '--duration[The duration of the entry. Format: hh:mm:ss. Example: 00:12:15]'
            '--description[The description of the entry]'
          )
          _arguments -C $timelog_add_help
          ;;

        compare)
          local timelog_compare_help=(
            '--range[The range shortcut: this-week, last-week, this-month, last-month]:range:(this-week last-week this-month last-month)'
            '--start[The start date. Example: 2022-10-01]'
            '--end[The end date. Example: 2022-10-30]'
            '--detailed[Whether to show the detailed information or not]'
            '--delta[Maximum time diff allowed, in seconds. Example: 60]'
            '--local-logs[The path to the CSV file with local time logs]'
            '--skip-lines[How many lines to skip from the CSV file]'
            '--help[Display the help text]'
          )
          _arguments -C $timelog_compare_help
          ;;

        summary)
          local timelog_summary_help=(
            '--range[The range shortcut: this-week, last-week, this-month, last-month]:range:(this-week last-week this-month last-month)'
            '--start[The start date. Example: 2022-10-01]'
            '--end[The end date. Example: 2022-10-30]'
            '--detailed[Whether to show the detailed information or not]'
            '--help[Display the help text]'
          )
          _arguments -C $timelog_summary_help
          ;;

        *)
          _arguments -C $help
          ;;
      esac

      ;;

    *)
      _arguments -C $help
      ;;

  esac


}

_clickup-cli "$@"
